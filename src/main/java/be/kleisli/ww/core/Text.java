package be.kleisli.ww.core;

/** Bounds what goes into the event buffer. */
public final class Text {

  /**
   * Cap for any payload stored on an event.
   *
   * <p>The buffer holds thousands of events and a single hook payload can carry a multi-megabyte
   * {@code tool_response}. Everything the UI actually shows — tool name, command, file path — is
   * read from small fields before this cap is applied.
   */
  public static final int DETAIL_LIMIT = 4000;

  private Text() {}

  public static String truncate(String value, int limit) {
    if (value == null) {
      return null;
    }
    return value.length() <= limit ? value : value.substring(0, limit) + "…";
  }

  public static String truncate(String value) {
    return truncate(value, DETAIL_LIMIT);
  }
}
