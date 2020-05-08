/*
 * Copyright 2019-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vividus.util;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

public final class UriUtils
{
    private static final char SCHEME_SEPARATOR = ':';
    private static final String USER_INFO_SEPARATOR = "@";
    private static final char QUERY_SEPARATOR = '?';
    private static final char FRAGMENT_SEPARATOR = '#';
    private static final char SLASH = '/';

    private UriUtils()
    {
    }

    public static URI addUserInfo(URI uri, String userInfo)
    {
        if (userInfo != null)
        {
            String host = uri.getHost();
            return createUri(uri.toString().replace(host, userInfo + USER_INFO_SEPARATOR + host));
        }
        return uri;
    }

    public static URI addUserInfoIfNotSet(URI url, String userInfo)
    {
        if (url.getUserInfo() == null)
        {
            return addUserInfo(url, userInfo);
        }
        return url;
    }

    public static boolean isFromTheSameSite(URI siteUri, URI uriToCheck)
    {
        return removeUserInfo(siteUri).getAuthority().equals(removeUserInfo(uriToCheck).getAuthority());
    }

    public static UserInfo getUserInfo(URI uri)
    {
        return Optional.ofNullable(uri.getUserInfo()).map(UriUtils::parseUserInfo).orElse(null);
    }

    public static UserInfo parseUserInfo(String userInfo)
    {
        int indexOfColon = userInfo.indexOf(':');
        String user = userInfo.substring(0, indexOfColon);
        String password = userInfo.substring(indexOfColon + 1);
        return new UserInfo(user, password);
    }

    public static URI removeUserInfo(URI uri)
    {
        return removePart(uri, URI::getRawUserInfo, p -> p + USER_INFO_SEPARATOR);
    }

    public static URI removeQuery(URI uri)
    {
        return removePart(uri, URI::getRawQuery, p -> QUERY_SEPARATOR + p);
    }

    public static URI removeFragment(URI uri)
    {
        return removePart(uri, URI::getFragment, p -> FRAGMENT_SEPARATOR + p);
    }

    public static URI removePart(URI uri, Function<URI, String> partExtractor, UnaryOperator<String> partDecorator)
    {
        String part = partExtractor.apply(uri);
        if (part != null)
        {
            return URI.create(StringUtils.remove(uri.toString(), partDecorator.apply(part)));
        }
        return uri;
    }

    public static URI createUri(String url)
    {
        try
        {
            String decodedUrl = decode(url);

            int schemeSeparatorIndex = decodedUrl.indexOf(SCHEME_SEPARATOR);
            if (schemeSeparatorIndex < 0)
            {
                throw new IllegalArgumentException("Scheme is missing in URL: " + url);
            }
            String scheme = decodedUrl.substring(0, schemeSeparatorIndex);
            String decodedFragment = extractDecodedFragment(url);

            if (scheme.startsWith("http"))
            {
                return buildUrl(decodedUrl, decodedFragment);
            }

            URI uri = new URI(scheme, removeFragment(decodedUrl, decodedFragment), decodedFragment);
            StringBuilder result = new StringBuilder(uri.getRawSchemeSpecificPart());
            if (decodedFragment != null)
            {
                result.append(FRAGMENT_SEPARATOR).append(uri.getRawFragment());
            }
            return URI.create(result.toString());
        }
        catch (URISyntaxException | MalformedURLException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    private static URI buildUrl(String decodedUrl, String decodedFragment)
            throws MalformedURLException, URISyntaxException
    {
        URL uri = new URL(decodedUrl);
        String path = uri.getPath();
        String query = uri.getQuery();
        String ref = uri.getRef();
        if (ref != null && !ref.equals(decodedFragment))
        {
            if (decodedFragment != null)
            {
                ref = removeFragment(ref, decodedFragment);
            }
            if (query != null)
            {
                query = query + FRAGMENT_SEPARATOR + ref;
            }
            else
            {
                path = path + FRAGMENT_SEPARATOR + ref;
            }
            ref = decodedFragment;
        }
        return normalizeToRfc3986(new URI(uri.getProtocol(), uri.getAuthority(), path, query, ref));
    }

    private static String encodePlusCharacter(String data)
    {
        return data.replace("+", "%2B");
    }

    private static String decode(String data)
    {
        return URLDecoder.decode(encodePlusCharacter(data), StandardCharsets.UTF_8);
    }

    private static String extractDecodedFragment(String url)
    {
        int fragmentStart = url.lastIndexOf(FRAGMENT_SEPARATOR);
        return fragmentStart >= 0 ? decode(url.substring(fragmentStart + 1)) : null;
    }

    /**
     * Partially transforms RFC 2396 compliant URI to RFC 3986 compliant one: percent-encode square brackets found in
     * URL query and URL fragment. For the complete list of required changes to make URI compliant with RFC 3986 see:
     * <a href="https://cr.openjdk.java.net/~dfuchs/writeups/updating-uri/">
     *     Updating URI support for RFC 3986 and RFC 3987 in the JDK
     * </a>
     * @param uri URI to normalize
     * @return URI partially compliant with RFC 3986
     */
    private static URI normalizeToRfc3986(URI uri)
    {
        StringBuilder normalizedUri = new StringBuilder(uri.toString());
        encodeSpecialCharacters(normalizedUri, uri.getRawQuery());
        encodeSpecialCharacters(normalizedUri, uri.getRawFragment());
        return URI.create(normalizedUri.toString());
    }

    private static String removeFragment(String url, String fragment)
    {
        if (fragment != null)
        {
            int fragmentStartIndex = url.length() - fragment.length();
            return url.substring(0, fragmentStartIndex - 1);
        }
        return url;
    }

    private static void encodeSpecialCharacters(StringBuilder normalizedUri, String uriPart)
    {
        if (uriPart != null)
        {
            String encodedUriPart = uriPart
                    .replace("[", "%5B")
                    .replace("]", "%5D");
            encodedUriPart = encodePlusCharacter(encodedUriPart);
            int indexOfUriPart = normalizedUri.indexOf(uriPart);
            normalizedUri.replace(indexOfUriPart, indexOfUriPart + uriPart.length(), encodedUriPart);
        }
    }

    public static URI buildNewUrl(String url, String relativeUrl)
    {
        return buildNewUrl(createUri(url), relativeUrl);
    }

    public static URI buildNewUrl(URI url, String relativeUrl)
    {
        int indexOfFirstNonSlashChar = StringUtils.indexOfAnyBut(relativeUrl, SLASH);
        String normalizedRelativeUrl = indexOfFirstNonSlashChar > 1
                ? relativeUrl.substring(indexOfFirstNonSlashChar - 1)
                : relativeUrl;
        try
        {
            URI parsedRelativeUrl = URI.create(normalizedRelativeUrl);
            if (url.isOpaque())
            {
                return new URI(url.getScheme(), url.getSchemeSpecificPart(), parsedRelativeUrl.getFragment());
            }

            String path = StringUtils.repeat(SLASH, indexOfFirstNonSlashChar - 1) + parsedRelativeUrl.getPath();
            return new URI(url.getScheme(), url.getAuthority(), path, parsedRelativeUrl.getQuery(),
                    parsedRelativeUrl.getFragment());
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Builds a new URL from base URL and relative URL
     * <br>A <b>base URL</b> - an absolute URL (e.g <code>https://example.com/path</code>).
     * <br>A <b>relative URL</b> pointing to any resource (e.g <code>/other</code>)
     * <br>
     * Examples:
     * <pre>
     * buildNewRelativeUrl(new URI("https://example.com/path"), "/test")  --&gt; https://example.com/test
     * buildNewRelativeUrl(new URI("https://example.com/path/"), "/test") --&gt; https://example.com/test
     * buildNewRelativeUrl(new URI("https://example.com/path"), "test")   --&gt; https://example.com/path/test
     * </pre>
     * @param baseUri Base URL
     * @param relativeUrl A string value of the relative URL
     * @return new URL built from base URL and relative URL
     */
    public static URI buildNewRelativeUrl(URI baseUri, String relativeUrl)
    {
        String pathToGo = relativeUrl;
        if (!pathToGo.startsWith(String.valueOf(SLASH)))
        {
            String currentPath = FilenameUtils.getFullPath(baseUri.getPath());
            if (currentPath.isEmpty())
            {
                currentPath = String.valueOf(SLASH);
            }
            pathToGo = currentPath + pathToGo;
        }
        return buildNewUrl(baseUri, pathToGo);
    }

    public static final class UserInfo
    {
        private final String user;
        private final String password;

        public UserInfo(String user, String password)
        {
            this.user = user;
            this.password = password;
        }

        public String getUser()
        {
            return user;
        }

        public String getPassword()
        {
            return password;
        }
    }
}
