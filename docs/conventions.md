# Conventions

How code is written here, and the one dependency decision that was measured rather than assumed.

- Comments explain *why*, especially where an obvious-looking alternative was rejected. Do not add
  comments that restate the code.
- Records for data, constructor injection for services.
- **No Lombok, deliberately.** It was considered and measured: version 1.18.46, the latest, silently
  generates nothing on JDK 26 - `@Getter` compiles and the getter simply does not exist. It works on
  17 and 21, so this is a JDK-26 incompatibility, not a configuration mistake. Lombok hooks into
  javac internals and has lagged every recent JDK release, so adopting it would mean pinning the
  compiler to an older JDK indefinitely. Records and constructor injection already cover most of
  what it would save here.
- No new dependencies without a reason that survives the "can the JDK already do this" question.
