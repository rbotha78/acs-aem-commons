/*
 * ACS AEM Commons
 *
 * Copyright (C) 2013 - 2023 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.rewriter.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.DynamicMBean;
import javax.management.NotCompliantMBeanException;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.rewriter.ProcessingComponentConfiguration;
import org.apache.sling.rewriter.ProcessingContext;
import org.apache.sling.rewriter.Transformer;
import org.apache.sling.rewriter.TransformerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import com.adobe.acs.commons.rewriter.ContentHandlerBasedTransformer;
import com.adobe.acs.commons.util.RequireAem;
import com.adobe.acs.commons.util.impl.AbstractGuavaCacheMBean;
import com.adobe.acs.commons.util.impl.CacheMBean;
import com.adobe.acs.commons.util.impl.exception.CacheMBeanException;
import com.adobe.granite.ui.clientlibs.ClientLibrary;
import com.adobe.granite.ui.clientlibs.HtmlLibrary;
import com.adobe.granite.ui.clientlibs.HtmlLibraryManager;
import com.adobe.granite.ui.clientlibs.LibraryType;
import com.day.cq.wcm.contentsync.PathRewriterOptions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * ACS AEM Commons - Versioned Clientlibs (CSS/JS) Rewriter
 * Re-writes paths to CSS and JS clientlibs to include the md5 checksum as a "
 * selector; in the form: /path/to/clientlib.123456789.css or /path/to/clientlib.min.1234589.css (if minification is enabled)
 * If the Enforce MD5 filter is enabled, the paths will be like /path/to/clientlib.ACSHASH123456789.css or /path/to/clientlib.min.ACSHASH1234589.css (if minification is enabled)
 */
@Component(metatype = true, label = "ACS AEM Commons - Versioned Clientlibs Transformer Factory",
    description = "Sling Rewriter Transformer Factory to add auto-generated checksums to client library references")
@Properties({
    @Property(name = "pipeline.type",
        value = "versioned-clientlibs", propertyPrivate = true),
    @Property(name = EventConstants.EVENT_TOPIC,
        value = "com/adobe/granite/ui/librarymanager/INVALIDATED", propertyPrivate = true),
    @Property(name = "jmx.objectname",
        value = "com.adobe.acs.commons.rewriter:type=VersionedClientlibsTransformerMd5Cache", propertyPrivate = true)
})
@Service(value = {DynamicMBean.class, TransformerFactory.class, EventHandler.class})
public final class VersionedClientlibsTransformerFactory extends AbstractGuavaCacheMBean<VersionedClientLibraryMd5CacheKey, String> implements TransformerFactory, EventHandler, CacheMBean {

    private static final Logger log = LoggerFactory.getLogger(VersionedClientlibsTransformerFactory.class);

    private static final int DEFAULT_MD5_CACHE_SIZE = 300;

    private static final boolean DEFAULT_DISABLE_VERSIONING = false;

    private static final boolean DEFAULT_ENFORCE_MD5 = false;

    @Property(label="MD5 Cache Size", description="Maximum size of the md5 cache.", intValue = DEFAULT_MD5_CACHE_SIZE)
    private static final String PROP_MD5_CACHE_SIZE = "md5cache.size";

    @Property(label="Disable Versioning", description="Should versioning of clientlibs be disabled", boolValue = DEFAULT_DISABLE_VERSIONING)
    private static final String PROP_DISABLE_VERSIONING = "disable.versioning";

    @Property(label="Enforce MD5", description="Enables a filter which returns a 404 error if the MD5 in the request does not match the expected value",
        boolValue = DEFAULT_ENFORCE_MD5)
    private static final String PROP_ENFORCE_MD5 = "enforce.md5";

    private static final String ATTR_SRC = "src";
    private static final String ATTR_HREF = "href";

    private static final String MIN_SELECTOR = "min";
    private static final String MIN_SELECTOR_SEGMENT = "." + MIN_SELECTOR;
    private static final String MD5_PREFIX = "ACSHASH";

    // pattern used to parse paths in the filter - group 1 = path; group 2 = md5; group 3 = extension
    private static final Pattern FILTER_PATTERN = Pattern.compile("(.*?)\\.(?:min.)?([a-zA-Z0-9]+)\\.(js|css)");
    private static final Pattern FILTER_PATTERN_ENFORCE_MD5 = Pattern.compile("(.*?)\\.(?:min.)?" + MD5_PREFIX + "([a-zA-Z0-9]+)\\.(js|css)");

    private static final String PROXY_PREFIX = "/etc.clientlibs/";
    private static final String PROXIED_STATIC_RESOURCE_PATH = "/resources/";

    private Cache<VersionedClientLibraryMd5CacheKey, String> md5Cache;

    private volatile Map<String, ClientLibrary> clientLibrariesCache;

    private boolean disableVersioning;

    private boolean enforceMd5;

    @Reference
    private HtmlLibraryManager htmlLibraryManager;
    
    // Disable this feature on AEM as a Cloud Service
    @Reference(target="(distribution=classic)")
    RequireAem requireAem;

    private ServiceRegistration<Filter> filterReg;

    public VersionedClientlibsTransformerFactory() throws NotCompliantMBeanException {
        super(CacheMBean.class);
    }

    @Activate
    @SuppressWarnings("squid:S1149")
    protected void activate(ComponentContext componentContext) {
        final BundleContext bundleContext = componentContext.getBundleContext();
        final Dictionary<?, ?> props = componentContext.getProperties();
        final int size = PropertiesUtil.toInteger(props.get(PROP_MD5_CACHE_SIZE), DEFAULT_MD5_CACHE_SIZE);
        this.md5Cache = CacheBuilder.newBuilder().recordStats().maximumSize(size).build();
        this.disableVersioning = PropertiesUtil.toBoolean(props.get(PROP_DISABLE_VERSIONING), DEFAULT_DISABLE_VERSIONING);
        this.enforceMd5 = PropertiesUtil.toBoolean(props.get(PROP_ENFORCE_MD5), DEFAULT_ENFORCE_MD5);
        if (enforceMd5) {
            Dictionary<String, Object> filterProps = new Hashtable<String, Object>();
            filterProps.put("sling.filter.scope", "REQUEST");
            filterProps.put("service.ranking", Integer.valueOf(0));

            filterReg = bundleContext.registerService(Filter.class,
                    new BadMd5VersionedClientLibsFilter(), filterProps);
        }
    }

    @Deactivate
    protected void deactivate() {
        if (filterReg != null) {
            filterReg.unregister();
            filterReg = null;
        }
        this.md5Cache = null;
        this.clientLibrariesCache = null;
    }

    public Transformer createTransformer() {
        return new VersionableClientlibsTransformer();
    }

    private Attributes versionClientLibs(final String elementName, final Attributes attrs, final SlingHttpServletRequest request) {
        if (SaxElementUtils.isCss(elementName, attrs)) {
            return this.rebuildAttributes(new AttributesImpl(attrs), attrs.getIndex("", ATTR_HREF),
                    attrs.getValue("", ATTR_HREF), LibraryType.CSS, request);

        } else if (SaxElementUtils.isJavaScript(elementName, attrs)) {
            String attributeName = StringUtils.equals(elementName, "script") ? ATTR_SRC : ATTR_HREF;
            return this.rebuildAttributes(new AttributesImpl(attrs), attrs.getIndex("", attributeName),
                    attrs.getValue("", attributeName), LibraryType.JS, request);

        } else {
            return attrs;
        }
    }

    private Attributes rebuildAttributes(final AttributesImpl newAttributes, final int index, final String path,
                                         final LibraryType libraryType, final SlingHttpServletRequest request) {
        final String contextPath = request.getContextPath();
        String libraryPath = path;
        if (StringUtils.isNotBlank(contextPath)) {
            libraryPath = path.substring(contextPath.length());
        }

        String versionedPath = this.getVersionedPath(libraryPath, libraryType, request);

        if (StringUtils.isNotBlank(versionedPath)) {
            if(StringUtils.isNotBlank(contextPath)) {
                versionedPath = contextPath + versionedPath;
            }
            log.debug("Rewriting to: {}", versionedPath);
            newAttributes.setValue(index, versionedPath);
        } else {
            log.debug("Versioned Path could not be created properly");
        }

        return newAttributes;
    }

    private String getVersionedPath(final String originalPath, final LibraryType libraryType,
            final SlingHttpServletRequest request) {
        if (originalPath.startsWith(PROXY_PREFIX) && originalPath.contains(PROXIED_STATIC_RESOURCE_PATH)) {
            log.debug("Static resource accessed via the clientlib proxy: '{}'", originalPath);
            return null;
        }
        try {
            boolean appendMinSelector = false;
            String libraryPath = StringUtils.substringBeforeLast(originalPath, ".");
            if (libraryPath.endsWith(MIN_SELECTOR_SEGMENT)) {
                appendMinSelector = true;
                libraryPath = StringUtils.substringBeforeLast(libraryPath, ".");
            }

            final HtmlLibrary htmlLibrary = getLibrary(libraryType, libraryPath, request);

            if (htmlLibrary != null) {
                StringBuilder builder = new StringBuilder();
                builder.append(libraryPath);
                builder.append(".");

                if (appendMinSelector) {
                    builder.append(MIN_SELECTOR).append(".");
                }
                if (enforceMd5) {
                    builder.append(MD5_PREFIX);
                }
                builder.append(getMd5(htmlLibrary));
                builder.append(libraryType.extension);

                return builder.toString();
            } else {
                log.debug("Could not find HtmlLibrary at path: {}", libraryPath);
                return null;
            }
        } catch (Exception ex) {
            // Handle unexpected formats of the original path
            log.error("Attempting to get a versioned path for [ {} ] but could not because of: {}", originalPath,
                    ex.getMessage());
            return originalPath;
        }
    }

    private HtmlLibrary getLibrary(LibraryType libraryType, String libraryPath, SlingHttpServletRequest request) {
        String resolvedLibraryPath = resolvePath(libraryType, libraryPath, request);
        return resolvedLibraryPath == null ? null : htmlLibraryManager.getLibrary(libraryType, resolvedLibraryPath);
    }

    private String resolvePath(LibraryType libraryType, String libraryPath, SlingHttpServletRequest request) {
        if (!libraryPath.startsWith(PROXY_PREFIX)) {
            Resource libraryResource = request.getResourceResolver().resolve(request, libraryPath);
            if (libraryResource != null && !(libraryResource instanceof NonExistingResource)) {
                return libraryResource.getPath();
            }
            // Default behavior, to keep consistency with previous implementation and to not return a null path in case
            // the resolver can't find the clientlib
            return libraryPath;
        }
        return resolveProxiedClientLibrary(libraryType, libraryPath, request.getResourceResolver(), true);
    }

    private String resolveProxiedClientLibrary(LibraryType libraryType, String proxiedPath, ResourceResolver resourceResolver, boolean refreshCacheIfNotFound) {
        final String relativePath = proxiedPath.substring(PROXY_PREFIX.length());
        for (final String prefix : resourceResolver.getSearchPath()) {
            final String absolutePath = prefix + relativePath;
            // check whether the ClientLibrary exists before calling HtmlLibraryManager#getLibrary in order
            // to avoid WARN log messages that are written when an unknown HtmlLibrary is requested
            if (hasProxyClientLibrary(libraryType, absolutePath)) {
                return absolutePath;
            }
        }

        if (refreshCacheIfNotFound) {
            // maybe the library has appeared and our copy of the cache is stale
            log.info("Refreshing client libraries cache, because {} could not be found", proxiedPath);
            clientLibrariesCache = null;
            return resolveProxiedClientLibrary(libraryType, proxiedPath, resourceResolver, false);
        }
        return null;
    }

    private boolean hasProxyClientLibrary(final LibraryType type, final String path) {
        ClientLibrary clientLibrary = getClientLibrary(path);
        return clientLibrary != null && clientLibrary.allowProxy() && clientLibrary.getTypes().contains(type);
    }

    private ClientLibrary getClientLibrary(String path) {
        if (clientLibrariesCache == null) {
            clientLibrariesCache = Collections.unmodifiableMap(htmlLibraryManager.getLibraries());
        }
        return clientLibrariesCache.get(path);
    }

    @NotNull private String getMd5(@NotNull final HtmlLibrary htmlLibrary) throws IOException, ExecutionException {
        return md5Cache.get(new VersionedClientLibraryMd5CacheKey(htmlLibrary), new Callable<String>() {

            @Override
            public String call() throws Exception {
                return calculateMd5(htmlLibrary, htmlLibraryManager.isMinifyEnabled());
            }
        });
    }


    @SuppressWarnings("squid:S2070") // MD5 not used cryptographically
    @NotNull private String calculateMd5(@NotNull final HtmlLibrary htmlLibrary, boolean isMinified) throws IOException {
        // make sure that the minified version is being request in case minification is globally enabled
        // as this will reset the dirty flag on the clientlib
        try (InputStream input = htmlLibrary.getInputStream(isMinified)) {
            return DigestUtils.md5Hex(input);
        }
    }

    private class VersionableClientlibsTransformer extends ContentHandlerBasedTransformer {

        private SlingHttpServletRequest request;
        
        private boolean enabled;

        @Override
        public void init(ProcessingContext context, ProcessingComponentConfiguration config) throws IOException {
            super.init(context, config);
            this.request = context.getRequest();
            // versioned clientlibs are not supported for Page Exports with cq-wcm-content-sync
            enabled = request.getAttribute(PathRewriterOptions.ATTRIBUTE_PATH_REWRITING_OPTIONS) == null;
        }

        public void startElement(final String namespaceURI, final String localName, final String qName,
                                 final Attributes attrs)
                throws SAXException {
            
            final Attributes nextAttributes;
            if (disableVersioning || !enabled) {
                nextAttributes = attrs;
            } else {
                nextAttributes = versionClientLibs(localName, attrs, request);
            }
            getContentHandler().startElement(namespaceURI, localName, qName, nextAttributes);
        }
    }

    @Override
    public void handleEvent(Event event) {
        String path = (String) event.getProperty(SlingConstants.PROPERTY_PATH);
        md5Cache.invalidate(new VersionedClientLibraryMd5CacheKey(path, LibraryType.JS));
        md5Cache.invalidate(new VersionedClientLibraryMd5CacheKey(path, LibraryType.CSS));
        clientLibrariesCache = null;
    }

    @Override
    protected Cache<VersionedClientLibraryMd5CacheKey, String> getCache() {
        return md5Cache;
    }

    @Override
    protected long getBytesLength(String cacheObj) {
        return cacheObj.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    @SuppressWarnings("squid:S1192")
    protected void addCacheData(Map<String, Object> data, String cacheObj) {
        data.put("Value", cacheObj);
    }

    @Override
    protected String toString(String cacheObj) throws CacheMBeanException {
        return cacheObj;
    }

    @Override
    @SuppressWarnings("squid:S1192")
    protected CompositeType getCacheEntryType() throws OpenDataException {
        return new CompositeType(JMX_PN_CACHEENTRY, JMX_PN_CACHEENTRY,
                new String[] { JMX_PN_CACHEKEY, "Value" },
                new String[] { JMX_PN_CACHEKEY, "Value" },
                new OpenType[] { SimpleType.STRING, SimpleType.STRING });
    }

    @NotNull
    UriInfo getUriInfo(@Nullable final String uri, @NotNull SlingHttpServletRequest request) {
        if (uri != null) {
            Matcher matcher;
            if (enforceMd5) {
                matcher = FILTER_PATTERN_ENFORCE_MD5.matcher(uri);
            } else {
                matcher = FILTER_PATTERN.matcher(uri);
            }
            if (matcher.matches()) {
                final String libraryPath = matcher.group(1);
                final String md5 = matcher.group(2);
                final String extension = matcher.group(3);

                LibraryType libraryType;
                if (LibraryType.CSS.extension.substring(1).equals(extension)) {
                    libraryType = LibraryType.CSS;
                } else {
                    libraryType = LibraryType.JS;
                }

                final HtmlLibrary htmlLibrary = getLibrary(libraryType, libraryPath, request);
                return new UriInfo(libraryPath + "." + extension, md5, libraryType, htmlLibrary);
            }
        }

        return new UriInfo("", "", null, null);
    }

    class BadMd5VersionedClientLibsFilter implements Filter {

        @Override
        @SuppressWarnings("squid:S3776")
        public void doFilter(final ServletRequest request,
                             final ServletResponse response,
                             final FilterChain filterChain) throws IOException, ServletException {
            if (request instanceof SlingHttpServletRequest && response instanceof SlingHttpServletResponse) {
                final SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
                final SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;
                String uri = slingRequest.getRequestURI();
                UriInfo uriInfo = getUriInfo(uri, slingRequest);
                if (uriInfo.cacheKey != null) {
                    if ("".equals(uriInfo.md5)) {
                        log.debug("MD5 is blank for '{}' in Versioned ClientLibs cache, allowing {} to pass", uriInfo.cleanedUri, uri);
                        filterChain.doFilter(request, response);
                        return;
                    }

                    String md5FromCache = null;
                    try {
                        md5FromCache = getCacheEntry(uriInfo.cacheKey);
                    } catch (Exception e) {
                        log.warn("Failed to get cache entry for '{}'", uriInfo.cacheKey);
                    }

                    // this static value "Invalid cache key parameter." happens when the cache key can't be
                    // found in the cache
                    if ("Invalid cache key parameter.".equals(md5FromCache)) {
                        md5FromCache = calculateMd5(uriInfo.htmlLibrary, htmlLibraryManager.isMinifyEnabled());
                    }

                    if (md5FromCache == null) {
                        // something went bad during the cache access
                        log.warn("Failed to fetch data from Versioned ClientLibs cache, allowing {} to pass", uri);
                        filterChain.doFilter(request, response);
                    } else {
                        // the file is in the cache, compare the md5 from cache with the one in the request
                        if (md5FromCache.equals(uriInfo.md5)) {
                            log.debug("MD5 equals for '{}' in Versioned ClientLibs cache, allowing {} to pass", uriInfo.cleanedUri, uri);
                            filterChain.doFilter(request, response);
                        } else {
                            log.info("MD5 differs for '{}' in Versioned ClientLibs cache. Expected {}. Sending 404 for '{}'",
                                    uriInfo.cleanedUri, md5FromCache, uri);
                            slingResponse.sendError(HttpServletResponse.SC_NOT_FOUND);
                        }
                    }
                } else {
                    filterChain.doFilter(request, response);
                }
            } else {
                filterChain.doFilter(request, response);
            }
        }

        @Override
        public void init(final FilterConfig filterConfig) throws ServletException {
            // no-op
        }

        @Override
        public void destroy() {
            // no-op
        }
    }

    static class UriInfo {
        private final String cleanedUri;
        private final String md5;
        private final HtmlLibrary htmlLibrary;
        private final String cacheKey;

        UriInfo(String cleanedUri, String md5, LibraryType libraryType, HtmlLibrary htmlLibrary) {
            this.cleanedUri = cleanedUri;
            this.md5 = md5;
            this.htmlLibrary = htmlLibrary;
            if (libraryType != null && htmlLibrary != null) {
                cacheKey = htmlLibrary.getLibraryPath() + libraryType.extension;
            } else {
                cacheKey = null;
            }
        }
    }
}