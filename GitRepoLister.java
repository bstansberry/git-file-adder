///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.15.0
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.0
//DEPS com.squareup.okhttp3:okhttp:4.12.0
//DEPS info.picocli:picocli:4.7.6
//DEPS org.kohsuke:github-api:1.327

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector;
import picocli.CommandLine;

@CommandLine.Command(name = "GitRepoLister", mixinStandardHelpOptions = true, version = "GitRepoLister 0.1", description = """
        The GitRepoLister writes to a file the URLs of all unarchived repos in a list of GitHub organizations
        """)
public class GitRepoLister implements Runnable {
    public static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(GitRepoLister.class.getPackageName());

    @CommandLine.Parameters(index = "0", description = "The organizations to check", split = ",")
    private List<String> organizations;

    @CommandLine.Option(names = { "-o",
            "--output-file" }, description = "Specify the GitHub organizations", defaultValue = "repositories.txt")
    private String outputFile;

    private final String cacheDir = System.getProperty("user.home") + "/.cache/git-repo-lister-cache";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GitRepoLister()).execute(args);
        System.exit(exitCode);
    }

    @SuppressWarnings("CallToPrintStackTrace")
    @Override
    public void run() {
        try {
            // Connect to GitHub
            GitHub github = setupGitHubClient();

            try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
                for (String organization : organizations) {
                    GHOrganization org = github.getOrganization(organization);
                    if (org == null) {
                        log.severe("Organization not found: " + organization);
                        continue;
                    }
                    log.info("❇️ Preparing to list repositories for organization " + org.getLogin());

                    List<GHRepository> allRepos = org.listRepositories().toList();
                    log.info("Found " + allRepos.size() + " candidate repositories");
                    List<GHRepository> filteredRepos = allRepos.stream()
                            .filter(repo -> !repo.isArchived())
                            .peek(repo -> writer.println(repo.getHtmlUrl()))
                            .toList();
                    log.info("Recorded " + filteredRepos.size() + " matching repositories");
                }
            }
            log.info("✔️ Repository list written to " + outputFile);
        } catch (IOException e) {
            log.severe("Error: " + e);
            e.printStackTrace();
        }
    }

    /**
     * Set up GitHub client with caching to reduce API calls
     */
    public GitHub setupGitHubClient() throws IOException {

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();

        if (ensureDirectoryExists(cacheDir)) {
            log.finest("Cache directory: " + cacheDir);
            Cache cache = new Cache(Path.of(cacheDir).toFile(), 10 * 1024 * 1024); // 10MB cache
            clientBuilder.cache(cache);
        } else {
            log.finest("Cannot create cache directory at " + cacheDir + " -- will use a non-caching GitHub API connector");
        }

        log.finest("Creating GitHub API connector");
        var connector = new OkHttpGitHubConnector(clientBuilder.build());

        GitHub gh = GitHubBuilder.fromPropertyFile()
                .withConnector(connector)
                .build();
        log.finest("Connected successfully");
        return gh;
    }

    private boolean ensureDirectoryExists(String dirPath) {
        Path path = Path.of(dirPath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
                return true;
            } catch (IOException e) {
                log.warning("⚠️ Failed to create directory: " + dirPath + " -- request caching will not be available");
                return false;
            }
        }
        return true;
    }
}
