package com.mikesamuel.url;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.net.MediaType;

/**
 * Bundles a URL with sufficient context to allow part-wise analysis.
 */
public final class URLValue {

  /** The context in which the URL is interpreted. */
  public final URLContext context;
  /** The original URL text. */
  public final String originalUrlText;
  /**
   * True if the authority component of the URL was not explicitly specified
   * in the original URL text and the authority is the placeholder authority.
   */
  public final boolean inheritsPlaceholderAuthority;

  /**
   * True iff simplifying the path interpreted ".." relative to "/" or "".
   * <p>
   * For example,
   * Interpreting "/../bar" relative to "http://example.com/foo/"
   * leads to simplying "http://example.com/foo/../bar" to
   * "http://example.com/bar".
   * But the "/.." is applied to "/foo" so root's parent is not reached.
   * <p>
   * On the other hand,
   * Interpreting "/../../bar" relative to "http://example.com/foo/"
   * leads to simplifying "http://example.com/foo/../../bar" to
   * "http://example.com/bar".
   * In this case, the first "/.." is applied to "/foo" and the second
   * is applied to "/" so the root's parent is reached.
   */
  public final boolean pathSimplificationReachedRootsParent;

  /** The full text of the URL after resolving against the context's base URL. */
  public final String urlText;

  /** The scheme of the URL or {@link Scheme#UNKNOWN} if not known. */
  public final Scheme scheme;
  /** The position of part boundaries within {@link #urlText}. */
  public final Scheme.PartRanges ranges;

  /**
   * @param context a context used to flesh out relative URLs.
   * @return a URL value with the given original text and whose
   *     urlText is an absolute URL.
   */
  public static URLValue of(URLContext context, String originalUrlText) {
    return new URLValue(
        Preconditions.checkNotNull(context),
        Preconditions.checkNotNull(originalUrlText));
  }

  /** Uses the default context. */
  public static URLValue of(String originalUrlText) {
    return new URLValue(URLContext.DEFAULT, originalUrlText);
  }


  private URLValue(URLContext context, String originalUrlText) {
    this.context = context;
    this.originalUrlText = originalUrlText;

    Absolutizer.Result abs = context.absolutizer.absolutize(originalUrlText);
    this.scheme  = abs.scheme;
    this.urlText = abs.absUrlText;
    this.ranges = abs.absUrlRanges;
    this.pathSimplificationReachedRootsParent = abs.pathSimplificationReachedRootsParent;
    final int phLen = URLContext.PLACEHOLDER_AUTHORITY.length();
    this.inheritsPlaceholderAuthority = this.ranges != null
        && abs.originalUrlRanges.authorityLeft < 0
        && this.ranges.authorityLeft >= 0
        && this.ranges.authorityRight - this.ranges.authorityLeft == phLen
        && URLContext.PLACEHOLDER_AUTHORITY.regionMatches(
            0, this.urlText, abs.absUrlRanges.authorityLeft, phLen);
  }

  private String authority;
  /** The authority or null if none is available. */
  public String getAuthority() {
    if (authority == null) {
      if (ranges != null && ranges.authorityLeft >= 0) {
        authority = urlText.substring(ranges.authorityLeft, ranges.authorityRight);
      }
    }
    return authority;
  }

  private String path;
  /** The path or null if none is available. */
  public String getPath() {
    if (path == null) {
      if (ranges != null && ranges.pathLeft >= 0) {
        path = urlText.substring(ranges.pathLeft, ranges.pathRight);
      }
    }
    return path;
  }

  private String query;
  /**
   * The query string or null if none is available.
   * This includes any leading '{@code ?}'.
   */
  public String getQuery() {
    if (query == null) {
      if (ranges != null && ranges.queryLeft >= 0) {
        query = urlText.substring(ranges.queryLeft, ranges.queryRight);
      }
    }
    return query;
  }

  private String fragment;
  /**
   * The fragment or null if none is available.
   * This includes any leading '{@code #}'.
   */
  public String getFragment() {
    if (fragment == null) {
      if (ranges != null && ranges.fragmentLeft >= 0) {
        fragment = urlText.substring(ranges.fragmentLeft, ranges.fragmentRight);
      }
    }
    return fragment;
  }

  private String contentMetadata;
  /**
   * The contentMetadata or null if none is available.
   * This includes any leading '{@code #}'.
   */
  public String getContentMetadata() {
    if (contentMetadata == null) {
      if (ranges != null && ranges.contentMetadataLeft >= 0) {
        contentMetadata = urlText.substring(
            ranges.contentMetadataLeft, ranges.contentMetadataRight);
      }
    }
    return contentMetadata;
  }

  private Optional<MediaType> mediaTypeOpt;
  /**
   * The media type for the associated content if specified in
   * the content metadata.
   */
  public MediaType getContentMediaType() {
    if (mediaTypeOpt == null) {
      mediaTypeOpt = Optional.absent();
      if (scheme == BuiltinScheme.DATA) {
        String metadata = getContentMetadata();
        if (metadata != null) {
          mediaTypeOpt = DataSchemeMediaTypeUtil.parseMediaTypeFromDataMetadata(
              metadata);
        }
      }
    }
    return mediaTypeOpt.orNull();
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof URLValue)) {
      return false;
    }
    URLValue that = (URLValue) o;
    return this.originalUrlText.equals(that.originalUrlText)
        && this.context.equals(that.context);
  }

  @Override
  public int hashCode() {
    return originalUrlText.hashCode() + 31 * context.hashCode();
  }
}


final class DataSchemeMediaTypeUtil {
  /**
   * RFC 2397 defines mediatype thus
   * """
   *         mediatype  := [ type "/" subtype ] *( ";" parameter )
   *         ...
   *         parameter  := attribute "=" value
   *     where ... "type", "subtype", "attribute" and "value" are
   *     the corresponding tokens from [RFC2045], represented using
   *     URL escaped encoding of [RFC2396] as necessary.
   * """
   * so we need to percent decode after identifying the "/" and ";"
   * boundaries.
   * <p>
   * In addition, parameter values may be quoted-strings per RFC 822
   * which allows \-escaping.
   * It is unclear whether quotes can be %-escaped.
   * <p>
   * A strict reading of this means that a ',' or ';' in a quoted
   * string is part of the parameter value.
   */
  private static final Pattern MEDIA_TYPE_PATTERN = Pattern.compile(
      ""
      + "^"
      + "([^/;\"]+)"  // type in group 1
      + "/"
      + "([^/;\"]+)"   // type in group 2
      + "("   // parameters in group 3
      +   "(?:[;]"  // each parameter is preceded by a semicolon
      +     "(?!=base64(?:;|\\z))"  // base64 is not a media type parameter.
      +     "(?:"
      +       "[^;\"%]"  // one character in a parameter key or value
      +       "|(?:\"|%22)(?:[^\\\\\"%]|\\\\.|%5c.|%(?!=22|5c))*(?:\"|%22)"  // quoted-string
      +       "|%(?!=22|5c)"  // encoded non-meta character
      +     ")*"  // end key=value loop
      +   ")*"  // end parameter loop
      + ")",  // end group 3
      Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

  static Optional<MediaType> parseMediaTypeFromDataMetadata(
      String contentMetadata) {
    Matcher m = MEDIA_TYPE_PATTERN.matcher(contentMetadata);
    if (!m.find()) {
      return Optional.absent();
    }
    String type = PctDecode.of(m.group(1)).orNull();
    String subtype = PctDecode.of(m.group(2)).orNull();
    if (type == null || subtype == null) {
      return Optional.absent();
    }
    MediaType mt;
    try {
      mt = MediaType.create(type, subtype);
    } catch (@SuppressWarnings("unused") IllegalArgumentException ex) {
      return Optional.absent();
    }

    String parameters = m.group(3);
    if (parameters != null) {
      Multimap<String, String> parameterValues = LinkedListMultimap.create();
      for (String parameter : parameters.split(";")) {
        if (parameter.isEmpty()) { continue; }
        int eq = parameter.indexOf('=');
        if (eq < 0) {
          return Optional.absent();
        }
        String key = PctDecode.of(parameter.substring(0, eq)).orNull();
        String value = PctDecode.of(parameter.substring(eq + 1)).orNull();
        if (key == null || value == null) {
          return Optional.absent();
        }
        value = maybeDecodeRFC822QuotedString(value);
        parameterValues.put(key, value);
      }
      try {
        mt = mt.withParameters(parameterValues);
      } catch (@SuppressWarnings("unused") IllegalArgumentException ex) {
        return Optional.absent();
      }
    }

    return Optional.of(mt);
  }

  private static String maybeDecodeRFC822QuotedString(String tokenOrQuotedString) {
    int n = tokenOrQuotedString.length();
    if (n >= 2 && '"' == tokenOrQuotedString.charAt(0)
        && '"' == tokenOrQuotedString.charAt(n - 1)) {
      StringBuilder sb = new StringBuilder(n - 2);
      for (int i = 1; i < n - 1; ++i) {
        char c = tokenOrQuotedString.charAt(i);
        if (c == '\\' && i + 1 < n) {
          sb.append(tokenOrQuotedString.charAt(i + 1));
          ++i;
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }
    return tokenOrQuotedString;
  }

}