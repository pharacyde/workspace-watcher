package be.kleisli.ww.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import be.kleisli.ww.claude.TranscriptLocator;
import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class UsageServiceTest {

  @TempDir Path tmp;

  private Path projects;
  private Path transcript;
  private UsageService usage;

  @BeforeEach
  void setUp() throws IOException {
    Path workspace = Files.createDirectory(tmp.resolve("project"));
    Path claudeHome = Files.createDirectory(tmp.resolve("claude"));
    projects =
        Files.createDirectories(
            claudeHome.resolve("projects").resolve(TranscriptLocator.escapeCwd(workspace)));
    transcript = projects.resolve("session-a.jsonl");
    Files.writeString(transcript, "");

    WatcherProperties props = new WatcherProperties();
    props.setWorkspace(workspace.toString());
    props.setClaudeHome(claudeHome.toString());
    props.setDatabase(tmp.resolve("state/events.db").toString());
    // Pinned so the test does not depend on how the developer's own machine is billed.
    props.setBilling("api");

    ObjectMapper mapper = new ObjectMapper();
    TranscriptLocator locator = new TranscriptLocator(props, new ActiveWorkspace(props));
    usage = new UsageService(locator, new Pricing(mapper, props), new Billing(props), mapper);
  }

  private void append(String model, long in, long out, long write5m, long write1h, long read)
      throws IOException {
    String line =
        """
        {"type":"assistant","message":{"model":"%s","usage":{"input_tokens":%d,\
        "output_tokens":%d,"cache_read_input_tokens":%d,\
        "cache_creation":{"ephemeral_5m_input_tokens":%d,"ephemeral_1h_input_tokens":%d}}}}
        """
            .formatted(model, in, out, read, write5m, write1h);
    Files.writeString(transcript, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
  }

  @Test
  @DisplayName("prices every kind of token at its own rate")
  void pricesEachTokenKind() throws IOException {
    // One million of each, on Opus 5 at $5 input and $25 output. Cache reads are a tenth of input
    // and one-hour writes are double it, so the five lines are 5 + 25 + 6.25 + 10 + 0.5.
    append("claude-opus-5", 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000);

    UsageService.Summary summary = usage.summarise(null);
    assertThat(summary.costUsd()).isCloseTo(46.75, within(0.001));
  }

  @Test
  @DisplayName("cache reads dominate a long session, and must not be counted as input")
  void cacheReadsArePricedSeparately() throws IOException {
    // The shape of a real session: a small prompt read against a large cached prefix. Counting
    // cache reads at the input rate would overstate this tenfold.
    append("claude-opus-5", 0, 0, 0, 0, 200_000_000);

    assertThat(usage.summarise(null).costUsd()).isCloseTo(100.0, within(0.01));
  }

  @Test
  @DisplayName("falls back to the undivided cache figure when the split is absent")
  void handlesUndividedCacheCreation() throws IOException {
    Files.writeString(
        transcript,
        """
        {"type":"assistant","message":{"model":"claude-opus-5","usage":\
        {"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":1000000}}}
        """,
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);

    UsageService.Summary summary = usage.summarise(null);
    // Counted as the cheaper five-minute write: 1M x $5 x 1.25.
    assertThat(summary.tokens().cacheWrite5m()).isEqualTo(1_000_000);
    assertThat(summary.costUsd()).isCloseTo(6.25, within(0.001));
  }

  @Test
  @DisplayName("adds up several models and keeps them apart")
  void separatesModels() throws IOException {
    append("claude-opus-5", 1_000_000, 0, 0, 0, 0);
    append("claude-haiku-4-5", 1_000_000, 0, 0, 0, 0);

    UsageService.Summary summary = usage.summarise(null);
    assertThat(summary.models()).hasSize(2);
    assertThat(summary.costUsd()).isCloseTo(6.0, within(0.001));
  }

  @Test
  @DisplayName("names an unpriced model rather than pricing it at zero")
  void unknownModelIsNamed() throws IOException {
    // A confident zero for a model nobody has priced is worse than admitting it is unknown.
    append("claude-from-the-future-9", 1_000_000, 1_000_000, 0, 0, 0);

    UsageService.Summary summary = usage.summarise(null);
    assertThat(summary.unpricedModels()).containsExactly("claude-from-the-future-9");
    assertThat(summary.models())
        .singleElement()
        .extracting(UsageService.ModelUsage::costUsd)
        .isNull();
  }

  @Test
  @DisplayName("prices what it can when one model among several is unknown")
  void pricesTheRestAroundAnUnknownModel() throws IOException {
    // The old behaviour voided the whole figure, so one locally-run model - which costs nothing -
    // hid the cost of everything else. Not hypothetical: across this machine's transcripts
    // Qwen3.6-35B-A3B-4bit appears 145 times.
    append("claude-opus-5", 1_000_000, 0, 0, 0, 0);
    append("Qwen3.6-35B-A3B-4bit", 5_000_000, 5_000_000, 0, 0, 0);

    UsageService.Summary summary = usage.summarise(null);
    assertThat(summary.costUsd()).isCloseTo(5.0, within(0.001));
    assertThat(summary.unpricedModels()).containsExactly("Qwen3.6-35B-A3B-4bit");
  }

  @Test
  @DisplayName("a model with no tokens is not worth mentioning")
  void ignoresZeroTokenModels() throws IOException {
    // Claude Code records synthetic assistant messages under "<synthetic>" with an all-zero usage
    // block. Naming those as unpriced would be noise about nothing.
    append("claude-opus-5", 1_000_000, 0, 0, 0, 0);
    append("<synthetic>", 0, 0, 0, 0, 0);

    UsageService.Summary summary = usage.summarise(null);
    assertThat(summary.unpricedModels()).isEmpty();
    assertThat(summary.costUsd()).isCloseTo(5.0, within(0.001));
  }

  @Test
  @DisplayName("says whether a cost figure is money anyone actually pays")
  void reportsBillingMode() throws IOException {
    append("claude-opus-5", 1_000_000, 0, 0, 0, 0);
    assertThat(usage.summarise(null).billedPerToken()).isTrue();
    assertThat(usage.summarise(null).billingMode()).isEqualTo("api");
  }

  @Test
  @DisplayName("scopes to one session when asked")
  void scopesToSession() throws IOException {
    append("claude-opus-5", 1_000_000, 0, 0, 0, 0);

    assertThat(usage.summarise("session-a").tokens().input()).isEqualTo(1_000_000);
    assertThat(usage.summarise("session-b").tokens().input()).isZero();
  }

  @Test
  @DisplayName("counts a message once however many blocks it was written as")
  void doesNotDoubleCountBlocksOfOneMessage() throws IOException {
    // Claude Code writes one record per content block - thinking, text, tool_use - and repeats the
    // identical, complete usage block on each. Summing them multiplied a message's tokens by how
    // many blocks it happened to have; measured on this project, 58% too high.
    for (String block : new String[] {"thinking", "text", "tool_use"}) {
      Files.writeString(
          transcript,
          """
          {"type":"assistant","message":{"id":"msg_1","model":"claude-opus-5",\
          "content":[{"type":"%s"}],"usage":{"input_tokens":2,"output_tokens":1466,\
          "cache_read_input_tokens":46196}}}
          """
              .formatted(block),
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND);
    }

    UsageService.Summary summary = usage.summarise(null);
    assertThat(summary.tokens().output()).isEqualTo(1466);
    assertThat(summary.tokens().cacheRead()).isEqualTo(46196);
  }

  @Test
  @DisplayName("two different messages are both counted")
  void countsDistinctMessages() throws IOException {
    for (String id : new String[] {"msg_1", "msg_2"}) {
      Files.writeString(
          transcript,
          """
          {"type":"assistant","message":{"id":"%s","model":"claude-opus-5",\
          "content":[{"type":"text"}],"usage":{"input_tokens":1000,"output_tokens":0}}}
          """
              .formatted(id),
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND);
    }

    assertThat(usage.summarise(null).tokens().input()).isEqualTo(2000);
  }

  @Test
  @DisplayName("re-reads a transcript that has grown")
  void refreshesWhenTranscriptGrows() throws IOException {
    append("claude-opus-5", 1_000_000, 0, 0, 0, 0);
    assertThat(usage.summarise(null).tokens().input()).isEqualTo(1_000_000);

    append("claude-opus-5", 500_000, 0, 0, 0, 0);
    assertThat(usage.summarise(null).tokens().input()).isEqualTo(1_500_000);
  }

  private void appendTo(Path file, String model, long in, long out) throws IOException {
    String line =
        """
        {"type":"assistant","message":{"model":"%s","usage":{"input_tokens":%d,\
        "output_tokens":%d}}}
        """
            .formatted(model, in, out);
    Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
  }

  @Test
  @DisplayName("counts what a subagent spent, on the bill of the session that delegated to it")
  void countsSubagentTokens() throws IOException {
    appendTo(transcript, "claude-opus-5", 1_000_000, 0);
    Path agent =
        Files.createDirectories(projects.resolve("session-a").resolve("subagents"))
            .resolve("agent-abc.jsonl");
    Files.writeString(agent, "");
    appendTo(agent, "claude-opus-5", 1_000_000, 0);

    // Measured on this machine before the fix: 5.5% of all tokens lived only in subagent files, so
    // the feed showed the subagent's work while the cost pretended it had been free.
    assertThat(usage.summarise(null).tokens().total()).isEqualTo(2_000_000);
    assertThat(usage.summarise("session-a").tokens().total()).isEqualTo(2_000_000);
  }

  @Test
  @DisplayName("does not put a subagent's tokens on another session's bill")
  void subagentTokensStayWithTheirSession() throws IOException {
    Path other = projects.resolve("session-b.jsonl");
    Files.writeString(other, "");
    appendTo(other, "claude-opus-5", 500_000, 0);
    Path agent =
        Files.createDirectories(projects.resolve("session-a").resolve("subagents"))
            .resolve("agent-abc.jsonl");
    Files.writeString(agent, "");
    appendTo(agent, "claude-opus-5", 1_000_000, 0);

    assertThat(usage.summarise("session-b").tokens().total()).isEqualTo(500_000);
  }
}
