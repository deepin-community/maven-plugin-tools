package org.apache.maven.tools.plugin.javadoc;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.apache.maven.tools.plugin.javadoc.FullyQualifiedJavadocReference.MemberType;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.proxy.ProxyUtils;
import org.codehaus.plexus.util.StringUtils;

/**
 * Allows to create links to a site generated by javadoc (incl. deep-linking).
 * The site may be either accessible (online) or non-accessible (offline) when using this class.
 */
class JavadocSite
{
    private static final String PREFIX_MODULE = "module:";

    final URI baseUri;

    final Settings settings;

    final Map<String, String> containedPackageNamesAndModules; // empty in case this an offline site

    final boolean requireModuleNameInPath;

    static final EnumMap<FullyQualifiedJavadocReference.MemberType, 
                         EnumSet<JavadocLinkGenerator.JavadocToolVersionRange>>
        VERSIONS_PER_TYPE;
    static
    {
        VERSIONS_PER_TYPE = new EnumMap<>( FullyQualifiedJavadocReference.MemberType.class );
        VERSIONS_PER_TYPE.put( MemberType.CONSTRUCTOR,
                                        EnumSet.of( JavadocLinkGenerator.JavadocToolVersionRange.JDK7_OR_LOWER,
                                                    JavadocLinkGenerator.JavadocToolVersionRange.JDK8_OR_9,
                                                    JavadocLinkGenerator.JavadocToolVersionRange.JDK10_OR_HIGHER ) );
        VERSIONS_PER_TYPE.put( MemberType.METHOD,
                                        EnumSet.of( JavadocLinkGenerator.JavadocToolVersionRange.JDK7_OR_LOWER,
                                                    JavadocLinkGenerator.JavadocToolVersionRange.JDK8_OR_9,
                                                    JavadocLinkGenerator.JavadocToolVersionRange.JDK10_OR_HIGHER ) );
        VERSIONS_PER_TYPE.put( MemberType.FIELD, EnumSet.of( JavadocLinkGenerator.JavadocToolVersionRange.JDK7_OR_LOWER,
                                                             JavadocLinkGenerator.JavadocToolVersionRange.JDK8_OR_9 ) );
    }

    JavadocLinkGenerator.JavadocToolVersionRange version; // null in case not yet known for online sites

    /**
     * Constructor for online sites having an accessible {@code package-list} or {@code element-list}.
     * @param url
     * @param settings
     * @throws IOException
     */
    JavadocSite( final URI url, final Settings settings )
        throws IOException
    {
        Map<String, String> containedPackageNamesAndModules;
        boolean requireModuleNameInPath = false;
        try
        {
            // javadoc > 1.2 && < 10
            containedPackageNamesAndModules = getPackageListWithModules( url.resolve( "package-list" ), settings );
        }
        catch ( FileNotFoundException e )
        {
            try
            {
                // javadoc 10+
                containedPackageNamesAndModules = getPackageListWithModules( url.resolve( "element-list" ), settings );

                Optional<String> firstModuleName =
                    containedPackageNamesAndModules.values().stream().filter( StringUtils::isNotBlank ).findFirst();
                if ( firstModuleName.isPresent() )
                {
                    // are module names part of the URL (since JDK11)?
                    try ( Reader reader =
                        getReader( url.resolve( firstModuleName.get() + "/module-summary.html" ).toURL(), null ) )
                    {
                        requireModuleNameInPath = true;
                    }
                    catch ( IOException ioe )
                    {
                        // ignore
                    }
                }
            }
            catch ( FileNotFoundException e2 )
            {
                throw new IOException( "Found neither 'package-list' nor 'element-list' below url " + url
                    + ". The given URL does probably not specify the root of a javadoc site or has been generated with"
                    + " javadoc 1.2 or older." );
            }
        }
        this.containedPackageNamesAndModules = containedPackageNamesAndModules;
        this.baseUri = url;
        this.settings = settings;
        this.version = null;
        this.requireModuleNameInPath = requireModuleNameInPath;
    }

    /** Constructor for offline sites. This throws {@link UnsupportedOperationException} 
     *  for {@link #hasEntryFor(Optional, Optional)}. */
    JavadocSite( final URI url, JavadocLinkGenerator.JavadocToolVersionRange version, boolean requireModuleNameInPath )
    {
        Objects.requireNonNull( url );
        this.baseUri = url;
        Objects.requireNonNull( version );
        this.version = version;
        this.settings = null;
        this.containedPackageNamesAndModules = Collections.emptyMap();
        this.requireModuleNameInPath = requireModuleNameInPath;
    }

    static Map<String, String> getPackageListWithModules( final URI url, final Settings settings )
        throws IOException
    {
        Map<String, String> containedPackageNamesAndModules = new HashMap<>();
        try ( BufferedReader reader = getReader( url.toURL(), settings ) )
        {
            String line;
            String module = null;
            while ( ( line = reader.readLine() ) != null )
            {
                // each line starting with "module:" contains the module name afterwards
                if ( line.startsWith( PREFIX_MODULE ) )
                {
                    module = line.substring( PREFIX_MODULE.length() );
                }
                else
                {
                    containedPackageNamesAndModules.put( line, module );
                }
            }
            return containedPackageNamesAndModules;
        }
    }

    static boolean findLineContaining( final URI url, final Settings settings, Pattern pattern )
        throws IOException
    {
        try ( BufferedReader reader = getReader( url.toURL(), settings ) )
        {
            return reader.lines().anyMatch( pattern.asPredicate() );
        }
    }

    public URI getBaseUri()
    {
        return baseUri;
    }

    public boolean hasEntryFor( Optional<String> moduleName, Optional<String> packageName )
    {
        if ( containedPackageNamesAndModules.isEmpty() )
        {
            throw new UnsupportedOperationException( "Operation hasEntryFor(...) is not supported for offline "
                + "javadoc sites" );
        }
        if ( packageName.isPresent() )
        {
            if ( moduleName.isPresent() )
            {
                String actualModuleName = containedPackageNamesAndModules.get( packageName.get() );
                if ( !moduleName.get().equals( actualModuleName ) )
                {
                    return false;
                }
            }
            else
            {
                if ( !containedPackageNamesAndModules.containsKey( packageName.get() ) )
                {
                    return false;
                }
            }
        }
        else if ( moduleName.isPresent() )
        {
            if ( !containedPackageNamesAndModules.containsValue( moduleName.get() ) )
            {
                return false;
            }
        }
        else
        {
            throw new IllegalArgumentException( "Either module name or package name must be set!" );
        }
        return true;
    }

    /**
     * Generates a link to a javadoc html page below the javadoc site represented by this object.
     * The link is not validated (i.e. might point to a non-existing page)
     * @param 
     * @return the (deep-)link towards a javadoc page
     * @throws IllegalArgumentException if no link can be created
     */
    public URI createLink( String packageName, String className )
    {
        try
        {
            if ( className.endsWith( "[]" ) )
            {
                // url must point to simple class
                className = className.substring( 0, className.length()  - 2 );
            }
            return createLink( baseUri, Optional.empty(), Optional.of( packageName ),
                               Optional.of( className ) );
        }
        catch ( URISyntaxException e )
        {
            throw new IllegalArgumentException( "Could not create link for " + packageName + "." + className, e );
        }
    }

    /**
     * Splits up a given binary name into package name and class name part.
     * @param binaryName a binary name according to 
     * <a href="https://docs.oracle.com/javase/specs/jls/se8/html/jls-13.html#jls-13.1">JLS 13.1</a>
     * @return a key value pair where the key is the package name and the value the class name
     * @throws IllegalArgumentException if no link can be created
     */
    static Map.Entry<String, String> getPackageAndClassName( String binaryName )
    {
     // assume binary name according to https://docs.oracle.com/javase/specs/jls/se8/html/jls-13.html#jls-13.1
        int indexOfDollar = binaryName.indexOf( '$' );
        if ( indexOfDollar >= 0 ) 
        {
            // emit some warning, as non resolvable: unclear which type of member follows if it is non digit
            throw new IllegalArgumentException( "Can only resolve binary names of top level classes" );
        }
        int indexOfLastDot = binaryName.lastIndexOf( '.' );
        if ( indexOfLastDot < 0 )
        {
            throw new IllegalArgumentException( "Resolving primitives is not supported. "
                + "Binary name must contain at least one dot: " + binaryName );
        }
        if ( indexOfLastDot == binaryName.length() - 1 )
        {
            throw new IllegalArgumentException( "Invalid binary name ending with a dot: " + binaryName );
        }
        String packageName = binaryName.substring( 0, indexOfLastDot );
        String className = binaryName.substring( indexOfLastDot + 1, binaryName.length() );
        return new AbstractMap.SimpleEntry<>( packageName, className );
    }

    /**
     * Generates a link to a javadoc html page below the javadoc site represented by this object.
     * The link is not validated (i.e. might point to a non-existing page)
     * @param javadocReference a code reference from a javadoc tag
     * @return  the (deep-)link towards a javadoc page
     * @throws IllegalArgumentException if no link can be created
     */
    public URI createLink( FullyQualifiedJavadocReference javadocReference )
        throws IllegalArgumentException
    {
        final Optional<String> moduleName;
        if ( !requireModuleNameInPath )
        {
            moduleName = Optional.empty();
        }
        else
        {
            moduleName =
                Optional.ofNullable( javadocReference.getModuleName().orElse( 
                    containedPackageNamesAndModules.get( javadocReference.getPackageName().orElse( null ) ) ) );
        }
        return createLink( javadocReference, baseUri, this::appendMemberAsFragment, moduleName );
    }

    static URI createLink( FullyQualifiedJavadocReference javadocReference, URI baseUri,
                           BiFunction<URI, FullyQualifiedJavadocReference, URI> fragmentAppender )
    {
        return createLink( javadocReference, baseUri, fragmentAppender, Optional.empty() );
    }

    static URI createLink( FullyQualifiedJavadocReference javadocReference, URI baseUri,
                           BiFunction<URI, FullyQualifiedJavadocReference, URI> fragmentAppender,
                           Optional<String> pathPrefix )
        throws IllegalArgumentException
    {
        try
        {
            URI uri = createLink( baseUri, javadocReference.getModuleName(), javadocReference.getPackageName(),
                                  javadocReference.getClassName() );
            return fragmentAppender.apply( uri, javadocReference );
        }
        catch ( URISyntaxException e )
        {
            throw new IllegalArgumentException( "Could not create link for " + javadocReference, e );
        }
    }

    static URI createLink( URI baseUri, Optional<String> moduleName, Optional<String> packageName,
                           Optional<String> className ) throws URISyntaxException
    {
        StringBuilder link = new StringBuilder();
        if ( moduleName.isPresent() )
        {
            link.append( moduleName.get() + "/" );
        }
        if ( packageName.isPresent() )
        {
            link.append( packageName.get().replace( '.', '/' ) );
        }
        if ( !className.isPresent() )
        {
            if ( packageName.isPresent() )
            {
                link.append( "/package-summary.html" );
            }
            else if ( moduleName.isPresent() )
            {
                link.append( "/module-summary.html" );
            }
        }
        else
        {
            link.append( '/' ).append( className.get() ).append( ".html" );
        }
        return  baseUri.resolve( new URI( null, link.toString(), null ) );
    }

    
    URI appendMemberAsFragment( URI url, FullyQualifiedJavadocReference reference )
    {
        try
        {
            return appendMemberAsFragment( url, reference.getMember(), reference.getMemberType() );
        }
        catch ( URISyntaxException | IOException e )
        {
            throw new IllegalArgumentException( "Could not create link for " + reference, e );
        }
    }

    // CHECKSTYLE_OFF: LineLength
    /**
     * @param url
     * @param optionalMember
     * @param optionalMemberType
     * @return
     * @throws URISyntaxException
     * @throws IOException
     * @see <a href=
     *      "https://github.com/openjdk/jdk8u-dev/blob/f0ac31998d8396d92b4ce99aa345c05e6fd0f02a/langtools/src/share/classes/com/sun/tools/doclets/formats/html/markup/HtmlDocWriter.java#L154">
     *      Name generation in Javadoc8</a>
     * @see <a href=
     *      "https://github.com/openjdk/jdk/tree/master/src/jdk.javadoc/share/classes/jdk/javadoc/internal/doclets/formats/html">Javadoc
     *      Tools Source since JDK10</a>
     * @see <a href=
     *      "https://github.com/openjdk/jdk/tree/jdk-9%2B181/langtools/src/jdk.javadoc/share/classes/jdk/javadoc/internal/doclets/formats/html">Javadoc
     *      Tools Source JDK9<a/>
     * @see <a href=
     *      "https://github.com/openjdk/jdk/tree/jdk8-b93/langtools/src/share/classes/com/sun/tools/javadoc">Javadoc
     *      Tools Source JDK8</a>
     */
    // CHECKSTYLE_ON: LineLength
    URI appendMemberAsFragment( URI url, Optional<String> optionalMember, Optional<MemberType> optionalMemberType )
        throws URISyntaxException, IOException
    {
        if ( !optionalMember.isPresent() )
        {
            return url;
        }
        MemberType memberType = optionalMemberType.orElse( null );
        final String member = optionalMember.get();
        String fragment = member;
        if ( version != null )
        {
            fragment = getFragmentForMember( version, member, memberType == MemberType.CONSTRUCTOR );
        }
        else
        {
            // try out all potential formats
            for ( JavadocLinkGenerator.JavadocToolVersionRange potentialVersion : VERSIONS_PER_TYPE.get( memberType ) )
            {
                fragment = getFragmentForMember( potentialVersion, member, memberType == MemberType.CONSTRUCTOR );
                if ( findAnchor( url, fragment ) )
                {
                    // only derive javadoc version if there is no ambiguity
                    if ( memberType == MemberType.CONSTRUCTOR || memberType == MemberType.METHOD )
                    {
                        version = potentialVersion;
                    }
                    break;
                }
            }
        }
        return new URI( url.getScheme(), url.getSchemeSpecificPart(), fragment );
    }

    /**
     * canonical format given by member is using parentheses and comma.
     * 
     * @param version
     * @param member
     * @param isConstructor
     * @return the anchor
     */
    static String getFragmentForMember( JavadocLinkGenerator.JavadocToolVersionRange version, String member,
                                        boolean isConstructor )
    {
        String fragment = member;
        switch ( version )
        {
            case JDK7_OR_LOWER:
                // separate argument by spaces
                fragment = fragment.replace( ",", ", " );
                break;
            case JDK8_OR_9:
                // replace [] by ":A"
                fragment = fragment.replace( "[]", ":A" );
                // separate arguments by "-", enclose all arguments in "-" for javadoc 8
                fragment = fragment.replace( '(', '-' ).replace( ')', '-' ).replace( ',', '-' );
                break;
            case JDK10_OR_HIGHER:
                if ( isConstructor )
                {
                    int indexOfOpeningParenthesis = fragment.indexOf( '(' );
                    if ( indexOfOpeningParenthesis >= 0 )
                    {
                        fragment = "&lt;init&gt;" + fragment.substring( indexOfOpeningParenthesis );
                    }
                    else
                    {
                        fragment = "&lt;init&gt;";
                    }
                }
                break;
            default:
                throw new IllegalArgumentException( "No valid version range given" );
        }
        return fragment;
    }

    boolean findAnchor( URI uri, String anchorNameOrId )
        throws MalformedURLException, IOException
    {
        return findLineContaining( uri, settings, getAnchorPattern( anchorNameOrId ) );
    }

    static Pattern getAnchorPattern( String anchorNameOrId )
    {
        // javadoc 17 uses"<section ... id=<anchor> >"
        return Pattern.compile( ".*(name|NAME|id)=\\\"" + Pattern.quote( anchorNameOrId ) + "\\\"" );
    }

    // ---------------
    // CHECKSTYLE_OFF: LineLength
    // the following methods are copies from private methods contained in
    // https://github.com/apache/maven-javadoc-plugin/blob/231316be785782b61d96783fad111325868cfa1f/src/main/java/org/apache/maven/plugins/javadoc/JavadocUtil.java
    // CHECKSTYLE_ON: LineLength
    // ---------------
    /** The default timeout used when fetching url, i.e. 2000. */
    public static final int DEFAULT_TIMEOUT = 2000;

    /**
     * Creates a new {@code HttpClient} instance.
     *
     * @param settings The settings to use for setting up the client or {@code null}.
     * @param url The {@code URL} to use for setting up the client or {@code null}.
     * @return A new {@code HttpClient} instance.
     * @see #DEFAULT_TIMEOUT
     * @since 2.8
     */
    private static CloseableHttpClient createHttpClient( Settings settings, URL url )
    {
        HttpClientBuilder builder = HttpClients.custom();

        Registry<ConnectionSocketFactory> csfRegistry =
            RegistryBuilder.<ConnectionSocketFactory>create()
                .register( "http", PlainConnectionSocketFactory.getSocketFactory() )
                .register( "https",  SSLConnectionSocketFactory.getSystemSocketFactory() ).build();

        builder.setConnectionManager( new PoolingHttpClientConnectionManager( csfRegistry ) );
        builder.setDefaultRequestConfig( RequestConfig.custom().setSocketTimeout( DEFAULT_TIMEOUT )
                                         .setConnectTimeout( DEFAULT_TIMEOUT ).setCircularRedirectsAllowed( true )
                                         .setCookieSpec( CookieSpecs.IGNORE_COOKIES ).build() );

        // Some web servers don't allow the default user-agent sent by httpClient
        builder.setUserAgent( "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)" );

        // Some server reject requests that do not have an Accept header
        builder.setDefaultHeaders( Arrays.asList( new BasicHeader( HttpHeaders.ACCEPT, "*/*" ) ) );

        if ( settings != null && settings.getActiveProxy() != null )
        {
            Proxy activeProxy = settings.getActiveProxy();

            ProxyInfo proxyInfo = new ProxyInfo();
            proxyInfo.setNonProxyHosts( activeProxy.getNonProxyHosts() );

            if ( StringUtils.isNotEmpty( activeProxy.getHost() )
                && ( url == null || !ProxyUtils.validateNonProxyHosts( proxyInfo, url.getHost() ) ) )
            {
                HttpHost proxy = new HttpHost( activeProxy.getHost(), activeProxy.getPort() );
                builder.setProxy( proxy );

                if ( StringUtils.isNotEmpty( activeProxy.getUsername() ) && activeProxy.getPassword() != null )
                {
                    Credentials credentials =
                        new UsernamePasswordCredentials( activeProxy.getUsername(), activeProxy.getPassword() );

                    CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials( AuthScope.ANY, credentials );
                    builder.setDefaultCredentialsProvider( credentialsProvider );
                }
            }
        }
        return builder.build();
    }

    static BufferedReader getReader( URL url, Settings settings )
        throws IOException
    {
        BufferedReader reader = null;

        if ( "file".equals( url.getProtocol() ) )
        {
            // Intentionally using the platform default encoding here since this is what Javadoc uses internally.
            reader = new BufferedReader( new InputStreamReader( url.openStream() ) );
        }
        else
        {
            // http, https...
            final CloseableHttpClient httpClient = createHttpClient( settings, url );

            final HttpGet httpMethod = new HttpGet( url.toString() );

            HttpResponse response;
            HttpClientContext httpContext = HttpClientContext.create();
            try
            {
                response = httpClient.execute( httpMethod, httpContext );
            }
            catch ( SocketTimeoutException e )
            {
                // could be a sporadic failure, one more retry before we give up
                response = httpClient.execute( httpMethod, httpContext );
            }

            int status = response.getStatusLine().getStatusCode();
            if ( status != HttpStatus.SC_OK )
            {
                throw new FileNotFoundException( "Unexpected HTTP status code " + status + " getting resource "
                    + url.toExternalForm() + "." );
            }
            else
            {
                int pos = url.getPath().lastIndexOf( '/' );
                List<URI> redirects = httpContext.getRedirectLocations();
                if ( pos >= 0 && isNotEmpty( redirects ) )
                {
                    URI location = redirects.get( redirects.size() - 1 );
                    String suffix = url.getPath().substring( pos );
                    // Redirections shall point to the same file, e.g. /package-list
                    if ( !location.getPath().endsWith( suffix ) )
                    {
                        throw new FileNotFoundException( url.toExternalForm() + " redirects to "
                            + location.toURL().toExternalForm() + "." );
                    }
                }
            }

            // Intentionally using the platform default encoding here since this is what Javadoc uses internally.
            reader = new BufferedReader( new InputStreamReader( response.getEntity().getContent() ) )
            {
                @Override
                public void close()
                    throws IOException
                {
                    super.close();

                    if ( httpMethod != null )
                    {
                        httpMethod.releaseConnection();
                    }
                    if ( httpClient != null )
                    {
                        httpClient.close();
                    }
                }
            };
        }

        return reader;
    }

    /**
     * Convenience method to determine that a collection is not empty or null.
     * 
     * @param collection the collection to verify
     * @return {@code true} if not {@code null} and not empty, otherwise {@code false}
     */
    public static boolean isNotEmpty( final Collection<?> collection )
    {
        return collection != null && !collection.isEmpty();
    }
}
