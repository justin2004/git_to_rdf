build: sparql-anything-0.8.0.jar buildimage

sparql-anything-0.8.0.jar:
	wget 'https://github.com/SPARQL-Anything/sparql.anything/releases/download/v0.8.0/sparql-anything-0.8.0.jar'

buildimage:
	docker build --build-arg=uid=`id -u` --build-arg=gid=`id -g` -t justin2004/git_to_rdf .
