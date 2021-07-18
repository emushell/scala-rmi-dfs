FROM adoptopenjdk:11-jdk-hotspot

RUN apt-get update -q \
  && apt-get upgrade -qq \
  && apt-get install -y --no-install-recommends \
        git \
        wget \
  && apt-get autoremove -yqq --purge \
  && rm -rf /var/lib/apt/lists/*

ARG SCALA_VERSION=2.13.6
RUN echo 'Installing scala...' \
  && wget "http://www.scala-lang.org/files/archive/scala-$SCALA_VERSION.tgz" \
  && tar xzf scala-$SCALA_VERSION.tgz -C /tmp/ \
  && mv /tmp/scala-$SCALA_VERSION /usr/local/scala \
  && rm -rf scala-$SCALA_VERSION.tgz \
  && rm -rf /tmp/*

# Set scala home for current installation
ENV SCALA_HOME /usr/local/scala
ENV PATH="${SCALA_HOME}/bin:${PATH}"

WORKDIR /code
CMD ["bash"]