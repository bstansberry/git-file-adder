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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector;
import picocli.CommandLine;

@CommandLine.Command(name = "GitOrganizationWriters", mixinStandardHelpOptions = true, version = "GitOrganizationWriters 0.1", description = """
        The GitOrganizationWriters script writes to a file information about accounts with write permissions to GitHub organizations.
        """)
public class GitOrganizationWriters implements Runnable {
    public static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(GitOrganizationWriters.class.getPackageName());

    @CommandLine.Parameters(index = "0", description = "The organizations to check", split = ",")
    private List<String> organizations;

    @CommandLine.Option(names = { "-o",
            "--output-file" }, description = "Name of the output file", defaultValue = "github-writers.md")
    private String outputFile;

    @CommandLine.Option(names = { "-a",
            "--include-archived" }, description = "Whether archived repositories should be included", defaultValue = "false")
    private boolean includeArchived;



    @CommandLine.Option(names = { "-d",
            "--detail-level" }, description = "Level of detail to output for each writer (personal, organizations, repositories, full)", defaultValue = "repositories")
    private String detailLevel;

    private final String cacheDir = System.getProperty("user.home") + "/.cache/git-repo-lister-cache";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GitOrganizationWriters()).execute(args);
        System.exit(exitCode);
    }

    @SuppressWarnings("CallToPrintStackTrace")
    @Override
    public void run() {
        try {
            DetailLevel outputLevel = Enum.valueOf(DetailLevel.class, detailLevel.toUpperCase());

            // Connect to GitHub
            GitHub github = setupGitHubClient();

            Map<String, User> writers = new HashMap<>();
            for (String organization : organizations) {
                processOrganization(github, organization, writers);
            }
            try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
                TreeSet<User> sortedWriters = new TreeSet<>(writers.values());
                for (User user : sortedWriters) {
                    String name = user.ghUser.getName();
                    if (name == null || name.isEmpty()) {
                        name = user.ghUser.getLogin();
                    }

                    writer.println("### " + name);
                    writer.println();
                    writer.println("**GitHub**: " + user.ghUser.getLogin() + "\\");
                    writer.println("**URL**: " + user.ghUser.getHtmlUrl());
                    writer.println();

                    if (outputLevel != DetailLevel.PERSONAL) {
                        for (Map.Entry<String, Map<String, RepositoryAccess>> entry : user.repositories.entrySet()) {
                            writer.println("#### Organization: " + entry.getKey());
                            writer.println();
                            if (outputLevel != DetailLevel.ORGANIZATIONS) {
                                for (Map.Entry<String, RepositoryAccess> entry2 : entry.getValue().entrySet()) {
                                    if (outputLevel == DetailLevel.REPOSITORIES) {
                                        writer.println(entry2.getValue().getNameSlug() + "  ");
                                    } else {
                                        writer.println(entry2.getValue() + "  ");
                                    }
                                }
                                writer.println();
                            }
                        }
                    }
                }
            }
            log.info("✔️ Writer list written to " + outputFile);
        } catch (IOException e) {
            log.severe("Error: " + e);
            e.printStackTrace();
        }
    }

    private void processOrganization(GitHub github, String organization, Map<String, User> writers) throws IOException {
        GHOrganization org = github.getOrganization(organization);
        if (org == null) {
            log.severe("Organization not found: " + organization);
            return;
        }
        log.info("❇️ Preparing to list owners for organization " + org.getLogin());

        Set<User> owners = new HashSet<>();
        for (GHUser user : org.listMembersWithRole("admin")) {
            owners.add(writers.computeIfAbsent(user.getLogin(), k -> new User(user)));
        }

        Map<String, Set<GHUser>> orgTeams = new HashMap<>();
        for (GHRepository repository : org.listRepositories()) {

            boolean archived = repository.isArchived();
            if (archived && !includeArchived) {
                continue;
            }

            for (User owner : owners) {
                owner.addOwnerAccess(organization, repository.getName(), archived);
            }
            for (GHTeam team : repository.getTeams()) {
                if (hasWritePermission(team)) {
                    log.info("❇️ Preparing to list collaborators for repository/team " + repository.getName() + "/" + team.getName());
                    for (GHUser member : orgTeams.computeIfAbsent(team.getName(), k -> safeGetMembers(team))) {
                        writers.computeIfAbsent(member.getLogin(), k -> new User(member))
                                .addTeamAccess(organization, repository.getName(), archived, team.getName());
                    }
                } else {
                    log.info("❇️ repository/team " + repository.getName() + "/" + team.getName() + " only has permission " + team.getPermission());
                }
            }
            log.info("❇️ Preparing to list collaborators with possible direct write permissions for repository " + repository.getName());

            for (GHUser collaborator : repository.listCollaborators()) {
                User user = writers.get(collaborator.getLogin());
                if (user == null || !user.hasRepositoryAccess(organization, repository.getName())) {
                    // This collaborator doesn't have write access as an owner or via a team;
                    // see if they have it as an individual
                    GHPermissionType permission = repository.getPermission(collaborator);
                    if (permission == GHPermissionType.ADMIN || permission == GHPermissionType.WRITE) {
                        writers.computeIfAbsent(collaborator.getLogin(), k -> new User(collaborator))
                                .addCollaboratorAccess(organization, repository.getName(), archived);
                    }
                }
            }
        }
    }

    private boolean hasWritePermission(GHTeam team) {
        return switch (team.getPermission()) {
            case "admin", "maintain", "push" -> true;
            default -> false;
        };
    }

    private Set<GHUser> safeGetMembers(GHTeam team) {
        try {
            return team.getMembers();
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

    private static class User implements Comparable<User> {

        private final GHUser ghUser;
        private final Map<String, Map<String, RepositoryAccess>> repositories = new TreeMap<>();

        private User(GHUser ghUser) {
            this.ghUser = ghUser;
        }

        @Override
        public int compareTo(@NotNull User o) {
            if (ghUser.getId() == o.ghUser.getId()) {
                return 0;
            }
            try {
                String ourName = ghUser.getName();
                if (ourName == null || ourName.isEmpty()) {
                    ourName = ghUser.getLogin();
                }
                String theirName = o.ghUser.getName();
                if (theirName == null || theirName.isEmpty()) {
                    theirName = o.ghUser.getLogin();
                }
                if (ourName == null) {
                    return -1;
                } else if (theirName == null) {
                    return 1;
                } else {
                    int retval = ourName.compareTo(theirName);
                    return retval == 0 ? -1 : retval;
                }
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }

        @Override
        public String toString() {
            return ghUser.getLogin();
        }

        private void addOwnerAccess(String organization, String repository, boolean archived) {
            getRepositoryAccess(organization, repository, archived).owner = true;
        }

        private void addCollaboratorAccess(String organization, String repository, boolean archived) {
            getRepositoryAccess(organization, repository, archived).collaborator = true;
        }

        private void addTeamAccess(String organization, String repository, boolean archived, String team) {
            getRepositoryAccess(organization, repository, archived).addTeamAccess(team);
        }

        private RepositoryAccess getRepositoryAccess(String organization, String repository, boolean archived) {
            return repositories.computeIfAbsent(organization, key -> new TreeMap<>())
                    .computeIfAbsent(repository, key -> new RepositoryAccess(repository, archived));
        }

        private boolean hasRepositoryAccess(String organization, String repository) {
            Map<String, RepositoryAccess> orgRepos = repositories.get(organization);
            return orgRepos != null && orgRepos.containsKey(repository);
        }
    }

    private static class RepositoryAccess {
        private final String repository;
        private final boolean archived;
        private boolean owner;
        private boolean collaborator;
        private Set<String> teams;

        private RepositoryAccess(String repository, boolean archived) {
            this.repository = repository;
            this.archived = archived;
        }

        private void addTeamAccess(String team) {
            if (teams == null) {
                teams = new TreeSet<>();
            }
            teams.add(team);
        }

        private String getNameSlug() {
            return repository
                    + (archived ? " (archived)" : "");
        }

        public String toString() {
            return getNameSlug()
                    + " -- "
                    + (owner ? "owner, " : "")
                    + (collaborator ? "collaborator, " : "")
                    + (teams != null ? "teams=" + teams.toString() : "");
        }

    }

    private enum DetailLevel {
        PERSONAL,
        ORGANIZATIONS,
        REPOSITORIES,
        FULL
    }
}

