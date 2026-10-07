# Plan: GitOrganizationGroupIds JBang Script

## Overview

Add a new JBang script `GitOrganizationGroupIds.java` that iterates through all repositories in a specified GitHub organization and writes a Markdown report containing:

- Repository name
- Whether the repository is archived
- The name of the default branch
- The value of the `<groupId>` element in any `pom.xml` found at the root of the default branch (or a sentinel value such as `N/A` if none exists)

The script follows every convention established by the existing scripts in this repo: same JBang header and dependency set, same GitHub client setup with OkHttp caching, same PicoCLI argument style, same `java.util.logging.Logger` pattern, and Markdown output via `PrintWriter`.

**Confirmed design decisions:**
- Archived repos are included by default; use `-a/--include-archived false` to exclude them.
- A comma-separated list of organizations is accepted (same pattern as `GitRepoLister` and `GitOrganizationWriters`).
- `N/A` is used as the sentinel value when no `pom.xml` exists or no top-level `<groupId>` element is found.

---

## Sub-Task 1 — Create `GitOrganizationGroupIds.java`

**Intent**
Implement the new JBang script as a single self-contained `.java` file in the project root, consistent with the other scripts.

**Expected Outcomes**
- `GitOrganizationGroupIds.java` exists at the repo root.
- Running `jbang GitOrganizationGroupIds.java <org>` produces a Markdown file listing every repository in the organization with the four required columns.
- The script exits non-zero on fatal errors (e.g. org not found).

**Todo List**
1. Copy the JBang shebang and `//DEPS` block verbatim from an existing script (`GitRepoLister.java` is the simplest reference). Add `//DEPS javax.xml.parsers` is not needed — the JDK ships the XML parser; no extra dependency required.
2. Add imports: standard I/O and NIO (`FileWriter`, `PrintWriter`, `Files`, `Path`), `java.io.StringReader`, `java.util.List`, OkHttp (`Cache`, `OkHttpClient`), GitHub API (`GHContent`, `GHOrganization`, `GHRepository`, `GitHub`, `GitHubBuilder`, `OkHttpGitHubConnector`), PicoCLI (`CommandLine`), and the JDK XML parser (`javax.xml.parsers.DocumentBuilderFactory`, `org.w3c.dom.Document`, `org.w3c.dom.NodeList`).
3. Declare the class `GitOrganizationGroupIds implements Runnable` with:
   - `static Logger log` (same pattern as other scripts).
   - `@Parameters(index = "0")` `organizations` — a `List<String>` with `split = ","` (same pattern as `GitRepoLister` and `GitOrganizationWriters`).
   - `@Option("-o/--output-file")` defaulting to `"group-ids.md"`.
   - `@Option("-a/--include-archived")` defaulting to `true`.
   - `cacheDir` field pointing to `~/.cache/git-organization-group-ids-cache`.
4. Implement `main()` via `new CommandLine(...).execute(args)` + `System.exit()`.
5. Implement `run()`:
   - Call `setupGitHubClient()`.
   - Open `PrintWriter` on `outputFile`.
   - Write a Markdown table header: `| Repository | Archived | Default Branch | Group ID |` with separator row.
   - Loop over each organization in `organizations`, fetch it via `github.getOrganization(name)`, log a warning and continue if `null`.
   - For each org, iterate `org.listRepositories().toList()`, sorted by repo name for deterministic output.
   - Filter out archived repos when `includeArchived` is `false`.
   - For each repo call `extractGroupId(repo)` and write a table row.
   - Log progress with emoji (❇️ start per org, ✔️ done at end).
6. Implement `extractGroupId(GHRepository repo)`:
   - Attempt `repo.getFileContent("pom.xml")` (fetches from the default branch).
   - If `null`, return `"N/A"`.
   - Read `content.read()` into a String.
   - Parse with `DocumentBuilderFactory` (namespace-aware, non-validating).
   - Find the **top-level** `<groupId>` (first child of the root `<project>` element, not a nested dependency's groupId).
   - Return the trimmed text content, or `"N/A"` if the element is absent.
   - Catch all exceptions and return `"error"` with a log warning so one bad repo does not abort the run.
7. Copy `setupGitHubClient()` and `ensureDirectoryExists()` verbatim from `GitRepoLister.java`, changing only the `cacheDir` value.

**Relevant Context**
- [`GitRepoLister.java`](GitRepoLister.java) — simplest existing script; primary structural template.
- [`GitOrganizationWriters.java`](GitOrganizationWriters.java) — reference for Markdown output pattern.
- [`GitFileAdder.java`](GitFileAdder.java) — reference for `GHContent` / `repo.getFileContent()` usage.
- JDK built-in XML parsing: `javax.xml.parsers.DocumentBuilderFactory` — no extra dependency needed.
- `GHRepository.getFileContent(String path)` fetches from the default branch when no ref is given.

**Status** — `[x] done`

---

## Sub-Task 2 — Update `README.md`

**Intent**
Document the new script in the project README following the same structure used for the existing scripts.

**Expected Outcomes**
- A `## GitOrganizationGroupIds` section appears in `README.md` after the existing script sections.
- The section includes: a brief description, basic usage example, description of the output format, and the full usage output from PicoCLI's `--help`.

**Todo List**
1. Add a `## GitOrganizationGroupIds` heading after the `## GitOrganizationWriters` section.
2. Write a one-paragraph description explaining what the script produces.
3. Add a "Basic Usage" subsection with a shell code block: `jbang GitOrganizationGroupIds.java ORGANIZATION_NAME`.
4. Note that output is written to `group-ids.md` by default and describe the four Markdown table columns.
5. Add a "Full usage description" subsection with a shell block containing the PicoCLI `--help` output (derive this from the `@Command` and `@Option`/`@Parameters` annotations in the new script).

**Relevant Context**
- [`README.md`](README.md) lines 145–189 — the `GitOrganizationWriters` section, which immediately precedes where this section should be inserted.

**Status** — `[x] done`
