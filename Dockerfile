FROM debian:11
RUN apt-get update && apt-get install -y leiningen git locales splitpatch
RUN sed -i '/en_US.UTF-8/s/^# //g' /etc/locale.gen && \
    locale-gen

# replaced at build time
ARG uid=1000
ARG gid=1000
ARG user=containeruser

RUN groupadd -g $gid $user || true
RUN useradd $user --uid $uid --gid $gid --home-dir /home/$user && \
    mkdir /home/$user && \
    chown $uid:$gid /home/$user
USER $user

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

WORKDIR /app

ADD queries/ /app/queries/
ADD project.clj /app/
ADD src/git_to_rdf/ /app/src/git_to_rdf/
ADD sparql-anything-0.8.0.jar /app/
USER root
RUN chmod -R 777 /app/
USER $user

WORKDIR /app

RUN lein uberjar

WORKDIR /mnt
