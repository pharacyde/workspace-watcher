package be.kleisli.ww.fs;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScratchProbeTest {
  @TempDir Path tmp;

  @Test
  void probe() throws IOException {
    Path workspace = Files.createDirectory(tmp.resolve("project"));
    WatcherProperties props = new WatcherProperties();
    props.setWorkspace(workspace.toString());
    FileTailService service = new FileTailService(new ActiveWorkspace(props), props);
    List<FileTailService.Chunk> chunks =
        service.follow("nope.txt").take(Duration.ofSeconds(2)).collectList().block();
    System.out.println("MISSING-FILE CHUNKS: " + (chunks == null ? -1 : chunks.size()));
    Path f = workspace.resolve("gone.log");
    Files.writeString(f, "a\n");
    List<FileTailService.Chunk> c2 =
        service
            .follow("gone.log")
            .doOnNext(
                c -> {
                  try {
                    Files.deleteIfExists(f);
                  } catch (IOException e) {
                    throw new IllegalStateException(e);
                  }
                })
            .take(Duration.ofSeconds(2))
            .collectList()
            .block();
    System.out.println("DELETED-MIDWAY CHUNKS: " + (c2 == null ? -1 : c2.size()));
    assertThat(true).isTrue();
  }
}
