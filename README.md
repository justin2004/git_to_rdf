# git_to_rdf

## what
A utility (that runs in a Docker container) that transforms a git repository on disk into RDF.
It uses [SPARQL Anything](https://github.com/SPARQL-Anything/sparql.anything) to transform the intermediate csv and json git repository representations into RDF using SPARQL construct queries.

## why
I've [blogged](https://github.com/justin2004/weblog/tree/master/git_repo_as_rdf#readme) about using this tool to view a git repository as RDF.

## how

Say you cloned this git repo to `/home/alice/repo/git_to_rdf`, your git repository of interest is at `/home/alice/repo/projX`, and you want to put the RDF this tool produces at `/home/alice/RDF`

First build the Docker image.

0) have [Docker](https://docs.docker.com/engine/install/) installed
0) have `make` and `wget` installed
0) cd to `/home/alice/repo/git_to_rdf`
0) in a `bash` shell run: `make build`

Now use the Docker image .

0) cd to `/home/alice/`
0) in a `bash` shell run: `/home/alice/repo/git_to_rdf/git_to_rdf.sh --repository /mnt/repo/projX --output /mnt/RDF`
0) find your 2 output files `summaries.nq` and `hunks.nq` at `/home/alice/RDF` and the log file `git_to_rdf.log` in your current directory.


## speed

It took 6 minutes to transform [this](https://github.com/SPARQL-Anything/sparql.anything) git repository (consisting of 1,122 commits) into 2,207,235 triples.



## notes

I don't have instructions on using the jar file directly because this utility uses the /tmp directory and it doesn't clean up after itself. Running it in a disposable Docker container means that /tmp directory goes away after it is done running. Also the utility needs `splitpatch` installed and that comes in the Docker image. 

Note the use of `/mnt` in the instructions. When you run `git_to_rdf.sh` your current directory gets mapped into the Docker container at `/mnt`. A drawback of this is that if you want to put the produced RDF at `/tmp/` and the git repo of interest lives at `/home/alice/repos/projX` you'd have to cd to `/` then run `/home/alice/repo/git_to_rdf/git_to_rdf.sh --repository /mnt/home/alice/repo/projX --output /mnt/tmp`.

I might address those shortcomings of the utility but my immediate goal is to use the RDF not polish a utility for tidy deployments.

Also, you'll find many TODOs in the source. I may get to those one day.
