package be.kleisli.ww.guard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Whether one tool call sends its input somewhere outside this machine, and where.
 *
 * <p>A pure classification of the call's text, not an observation of the network (P18-05): it says
 * what the payload looks like, never what left the machine. The false negatives - {@code curl -T
 * file}, a token in {@code $TOKEN}, a script that POSTs on its own - are listed in
 * docs/collectors.md under "Guard".
 *
 * @param remote whether the call is judged to send data off this machine
 * @param host the first host named in the call, when one could be read; also filled for a local
 *     target so the reader can see why it was not counted as remote
 */
public record RemoteTarget(boolean remote, String host) {

  public static final RemoteTarget NONE = new RemoteTarget(false, null);

  private static final Pattern URL_HOST =
      Pattern.compile(
          "[a-z][a-z0-9+.-]*://(?:[^/\\s@]*@)?(\\[[0-9a-fA-F:.]+]|[A-Za-z0-9][A-Za-z0-9.-]*)",
          Pattern.CASE_INSENSITIVE);

  /** {@code user@host:} or a bare {@code host:} as scp and rsync take it; not a URL scheme. */
  private static final Pattern SCP_HOST =
      Pattern.compile("(?:^|\\s)(?:[\\w.-]+@)?([A-Za-z0-9][A-Za-z0-9.-]*):(?![/]{2})");

  private static final Pattern CURL_UPLOAD =
      Pattern.compile(
          "(?:^|\\s)(?:-d|--data(?:-\\w+)?|-F|--form|-T|--upload-file|--json"
              + "|-X\\s*(?:POST|PUT|PATCH)|--request[\\s=](?:POST|PUT|PATCH))(?=\\s|=|$)");
  private static final Pattern WGET_UPLOAD =
      Pattern.compile(
          "(?:^|\\s)(?:--post-data|--post-file|--body-data|--body-file"
              + "|--method[\\s=](?:POST|PUT|PATCH))(?=\\s|=|$)");
  private static final Pattern OTHER_UPLOAD =
      Pattern.compile(
          verb("git\\s+push")
              + "|"
              + verb("gh\\s+(?:api|gist|release)")
              + "|"
              + verb("aws\\s+s3\\s+(?:cp|sync)")
              + "|"
              + verb("npm\\s+publish"));

  private static final Pattern CURL = Pattern.compile(verb("curl"));
  private static final Pattern WGET = Pattern.compile(verb("wget"));
  private static final Pattern SCP = Pattern.compile(verb("scp"));
  private static final Pattern RSYNC = Pattern.compile(verb("rsync"));
  private static final Pattern SSH = Pattern.compile(verb("ssh") + "\\s+(.*)");

  /**
   * A command word on its own: {@code rsyncd.log} is not {@code rsync}, but {@code /usr/bin/curl}
   * is {@code curl}.
   */
  private static String verb(String word) {
    return "(?<![\\w-])" + word + "(?![\\w-])";
  }

  public static RemoteTarget of(String tool, JsonNode input) {
    if (tool == null) {
      return NONE;
    }
    if (tool.startsWith("mcp__")) {
      return new RemoteTarget(true, urlHost(input == null ? "" : input.toString()));
    }
    return switch (tool) {
      case "WebFetch", "WebSearch" -> new RemoteTarget(true, urlHost(text(input, "url")));
      case "Bash" -> ofCommand(text(input, "command"));
      default -> NONE;
    };
  }

  private static RemoteTarget ofCommand(String command) {
    if (command == null || command.isBlank()) {
      return NONE;
    }
    String host = null;
    boolean upload = false;
    if (CURL.matcher(command).find() && CURL_UPLOAD.matcher(command).find()) {
      upload = true;
    } else if (WGET.matcher(command).find() && WGET_UPLOAD.matcher(command).find()) {
      upload = true;
    } else if (SCP.matcher(command).find() || RSYNC.matcher(command).find()) {
      // Without a host the copy is local, whatever the verb.
      host = scpHost(command);
      upload = host != null || URL_HOST.matcher(command).find();
    } else if (OTHER_UPLOAD.matcher(command).find()) {
      upload = true;
    } else {
      Matcher ssh = SSH.matcher(command);
      if (ssh.find()) {
        host = sshHost(ssh.group(1));
        upload = host != null;
      }
    }
    if (!upload) {
      return NONE;
    }
    if (host == null) {
      host = urlHost(command);
    }
    return new RemoteTarget(!isLocal(host), host);
  }

  private static String text(JsonNode input, String field) {
    return input == null ? null : input.path(field).asString(null);
  }

  /** The host of the first URL in the text, or null. */
  static String urlHost(String text) {
    if (text == null) {
      return null;
    }
    Matcher m = URL_HOST.matcher(text);
    return m.find() ? m.group(1) : null;
  }

  private static String scpHost(String command) {
    Matcher m = SCP_HOST.matcher(command);
    return m.find() ? m.group(1) : null;
  }

  /** First word after {@code ssh} that is not a flag or a flag's numeric argument. */
  private static String sshHost(String rest) {
    for (String word : rest.split("\\s+")) {
      if (word.isEmpty()
          || word.startsWith("-")
          || word.contains("=")
          || word.chars().allMatch(Character::isDigit)) {
        continue;
      }
      int at = word.indexOf('@');
      return at >= 0 ? word.substring(at + 1) : word;
    }
    return null;
  }

  /** A target on this machine is not "outbound", whatever verb carried it. */
  static boolean isLocal(String host) {
    if (host == null) {
      return false;
    }
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost")
        || h.endsWith(".localhost")
        || h.endsWith(".local")
        || h.startsWith("127.")
        || h.equals("0.0.0.0")
        || h.equals("[::1]")
        || h.equals("::1");
  }
}
