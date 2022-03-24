FROM python:3.9-slim

COPY requirements.txt requirements.txt 

RUN pip install -r requirements.txt 

RUN apt-get update && apt-get install -y curl openjdk-17-jdk

RUN curl -LO https://github.com/babashka/babashka/releases/download/v0.7.8/babashka-0.7.8-linux-amd64.tar.gz && \
	gzip -cd babashka-0.7.8-linux-amd64.tar.gz | tar xvf - && rm babashka*.tar.gz
