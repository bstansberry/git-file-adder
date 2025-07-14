# git-file-adder
JBang script for adding a one or more files to multiple repositories in a GitHub organization.

The use case that inspired this script was the need to add a Developer Certificate of Origin file to hundreds of repositories in the Github `wildfly` and `wildfly-extras` organization.

## Basic Usage

Clone the repo locally and then:


```shell
cd git-file-adder
jbang GitFileAdder.java -o ORGANIZATION_NAME LOCAL_PATH_TO_FILE
```

This basic usage will find all the unarchived repositories in the GitHub organization "ORGANIZATION_NAME". It will create a branch named `git-file-adder` in each repo and add a file whose name is the filename element in LOCAL_PATH_TO_FILE to it. It will then create a PR to merge the `git-file-adder` branch into the repository's default branch.

## Adding multiple files

Multiple files can be added by simply listing them:

```shell
cd git-file-adder
jbang GitFileAdder.java -o ORGANIZATION_NAME LOCAL_PATH_TO_FILE1 LOCAL_PATH_TO_FILE2 LOCAL_PATH_TO_FILE3
```
## Selecting repositories

If adding the file to only a subset of the organizations repositories are wanted, use the `-l` or `-r` options to provide either a comma-delimited list of repository names (with `-l` or `--repo-list`) or a regex against which repository names must match (with `-r` or `--repo-regex`).

Providing both a repository list and a regex is not supported.

## Updating existing files

By default, the update to a repository will fail if one of the files to 'add' already exists. Use the `-u` or `--update-existing` option to configure the script to instead update the existing file.

## Error handling

The script will fail if there is a problem ingesting any of the files, i.e. reading them and storing them in in-memory byte arrays for later use.

Once the script begins iterating through the available repositories, a failure updating an individual repository will not abort processing. The other repositories will be attempted.

If processing for a repository succcessfully creates a topic branch but later fails, the topic branch will be deleted.

## Full usage description

The script provides a number of other options:

```shell
Usage: GitFileAdder [-huV] [-b=<baseBranch>] [-m=<message>] -o=<organization>
                    [-p=<path>] [-r=<repoRegex>] [-t=<topicBranch>]
                    [-l=<repoList>[,<repoList>...]]... <files>...
The GitFileAdder creates a PR adding one or more files to one or more
repositories in a GitHub organization

      <files>...          The files to add
  -b, --base-branch=<baseBranch>
                          Specify the name of the target branch for the file.
                            If unset each repo's default branch will be used.
  -h, --help              Show this help message and exit.
  -l, --repo-list=<repoList>[,<repoList>...]
                          Specify a comma delimited list of repository names
  -m, --pr-message=<message>
                          Message for the PR to merge the topic branch to the
                            base branch
  -o, --organization=<organization>
                          Specify the GitHub organization
  -p, --path=<path>       Path within the repository where files should be added
  -r, --repo-regex=<repoRegex>
                          Specify a regular expression to match repository names
  -t, --topic-branch=<topicBranch>
                          Name of the topic branch to create and add files to
  -u, --update-existing   Set to true if any existing file should be updated;
                            false means an existing file will result in failure
  -V, --version           Print version information and exit.
```