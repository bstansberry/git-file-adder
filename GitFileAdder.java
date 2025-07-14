///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.15.0
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.0
//DEPS com.squareup.okhttp3:okhttp:4.12.0
//DEPS info.picocli:picocli:4.7.6
//DEPS org.kohsuke:github-api:1.327

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "GitFileAdder", mixinStandardHelpOptions = true, version = "GitFileAdder 0.1", description = """
        The GitFileAdder creates a PR adding one or more files to one or more repositories in a GitHub organization
        """)
public class GitFileAdder implements Runnable {
    public static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(GitFileAdder.class.getPackageName());

    @Parameters(index = "0", description = "The files to add", arity = "1..*")
    private List<File> files;

    @Option(names = { "-o",
            "--organization" }, description = "Specify the GitHub organization", required= true)
    private String organization;

    @Option(names = { "-r",
            "--repo-regex" }, description = "Specify a regular expression to match repository names", defaultValue = ".*")
    private String repoRegex;

    @Option(names = { "-l",
            "--repo-list" }, description = "Specify a comma delimited list of repository names", split = ",")
    private List<String> repoList;

    @CommandLine.Option(names = { "-b",
            "--base-branch" }, description = "Specify the name of the target branch for the file. If unset each repo's default branch will be used.")
    private String baseBranch;

    @Option(names = { "-t",
            "--topic-branch" }, description = "Name of the topic branch to create and add files to", defaultValue = "git-file-adder")
    private String topicBranch;

    @Option(names = { "-m",
            "--pr-message" }, description = "Message for the PR to merge the topic branch to the base branch")
    private String message;

    @Option(names = { "-p",
            "--path" }, description = "Path within the repository where files should be added", defaultValue = "")
    private String path;

    @Option(names = { "-u",
            "--update-existing" }, description = "Set to true if any existing file should be updated; " +
            "false means an existing file will result in failure", defaultValue = "false")
    private boolean updateExisting;

    private final String cacheDir = System.getProperty("user.home") + "/.cache/git-file-adder-cache";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GitFileAdder()).execute(args);
        System.exit(exitCode);
    }

    @SuppressWarnings("CallToPrintStackTrace")
    @Override
    public void run() {
        try {
            boolean listBased = repoList != null && ! repoList.isEmpty();
            if (listBased && repoRegex != null && !".*".equals(repoRegex)) {
                log.severe("Both --repo-regex and --repo-list were configured. Choose one or the other.");
                return;
            }
            log.info("❇️ Ingesting files");

            Map<String, byte[]> contentMap = createContentMap();

            // Connect to GitHub
            GitHub github = setupGitHubClient();
            GHOrganization org = github.getOrganization(organization);
            if (org == null) {
                log.severe("Organization not found: " + organization);
                return;
            }
            log.info("❇️ Preparing to add files to organization " + org.getLogin());

            // Fetch all repositories based on regex

            if (listBased) {
                log.info("Fetching repositories matching list: " + repoList);
            } else {
                log.info("Fetching repositories matching pattern: " + repoRegex);
            }
            Pattern repoPattern = Pattern.compile(repoRegex);
            List<GHRepository> allRepos = org.listRepositories().toList();
            log.info("Found " + allRepos.size() + " candidate repositories");
            List<GHRepository> filteredRepos = allRepos.stream()
                    .filter(repo -> !repo.isArchived())
                    .filter(repo -> !listBased || repoList.contains(repo.getName()))
                    .filter(repo -> listBased || repoPattern.matcher(repo.getName()).matches())
                    .toList();
            log.info("Found " + filteredRepos.size() + " matching repositories");

            int count = 0;
            for (GHRepository repo : filteredRepos) {
                if (addFilesToRepo(repo, contentMap)) {
                    count++;
                }
            }

            if (count == filteredRepos.size()) {
                log.info("🎉 " + count + " PRs adding files were submitted for " + organization);
            } else if (count == 0) {
                log.severe("❌ Failed adding to add files to any repositories");
            } else {
                log.warning("⚠️ " + count + " PRs adding files were submitted for " + organization +
                        "; submitting PRs to " + (filteredRepos.size() - count) + " repositories failed");
            }
        } catch (IOException e) {
            log.severe("Error: " + e);
            e.printStackTrace();
        }
    }

    private Map<String, byte[]> createContentMap() {
        try {
            String dir = path.isEmpty() ? path : path.endsWith("/") ? path : path + "/";
            Map<String, byte[]> map = new LinkedHashMap<>();
            for (File file : files) {
                map.put(dir + file.getName(), Files.readAllBytes(file.toPath()));
            }
            return map;
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
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

    private boolean addFilesToRepo(GHRepository repo, Map<String, byte[]> contentMap) {
        log.info("Adding files  for " + repo.getName());
        GHRef newBranch = null;
        try {
            String branchName = baseBranch != null && !baseBranch.isEmpty() ? baseBranch : repo.getDefaultBranch();
            String sha1 = repo.getBranch(branchName).getSHA1();
            newBranch = repo.createRef("refs/heads/" + topicBranch, sha1);
            addContent(repo, topicBranch, contentMap);
            GHPullRequest pr = repo.createPullRequest(getPRMessage(), topicBranch, branchName, "Created by git-file-adder");
            log.info("❇️ Created pull request at " + pr.getUrl());
            return true;
        } catch (Exception e) {
            log.severe("❌ Failed adding to repo " + repo.getName() + " due to " + e);
            e.printStackTrace();
            if (newBranch != null) {
                try {
                    newBranch.delete();
                    log.info("Cleaned up by deleting topic branch " + topicBranch + " from repo " + repo.getName());
                } catch (IOException ioe) {
                    log.severe("Failed to clean up repo " + repo.getName() + " by deleting branch " + topicBranch + " due to " + ioe);
                    e.printStackTrace();
                }
            }
        }
        return false;
    }

    private void addContent(GHRepository repo, String branchName, Map<String, byte[]> contentMap) throws IOException {
        for (Map.Entry<String, byte[]> entry : contentMap.entrySet()) {
            String currentSha = updateExisting ? getCurrentSha(repo, branchName, entry.getKey()) : null;
            GHContentBuilder builder = repo.createContent()
                    .branch(branchName)
                    .message("Add " + entry.getKey())
                    .path(entry.getKey())
                    .content(entry.getValue());
            if (currentSha != null) {
                builder.sha(currentSha);
            }
            try {
                builder.commit();
            } catch (IOException ioe) {
                if (!updateExisting) {
                    // See if the file already exists; if so fail with a more useful message
                    currentSha = getCurrentSha(repo, branchName, entry.getKey());
                    if (currentSha != null) {
                        throw new IllegalStateException(
                                String.format("Repository %s already has content with path %s in branch %s; " +
                                "the --update-existing option must be used to update content",
                                        repo.getName(), entry.getKey(), branchName)
                                , ioe);
                    }
                }
                throw ioe;
            }
        }
    }

    private String getCurrentSha(GHRepository repo, String branchName, String contentPath) {
        String result = null;
        try {
            GHContent content = repo.getFileContent(contentPath, "refs/heads/" + branchName);
            if (content != null) {
                result = content.getSha();
            }
        } catch (IOException ioe) {
            // assume it means no content
        }
        return result;
    }

    private String getPRMessage() {
        if (message != null && !message.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder("Add ");
        boolean first = true;
        for (File f : files) {
            if (first) {
                first = false;
            } else {
                sb.append((", "));
            }
            sb.append(f.getName());
        }
        if (path !=null && !path.isEmpty()) {
            sb.append( "to ").append(path);
        }
        return sb.toString();
    }
}
