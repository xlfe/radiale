docker build . -t radiale-dev -f Dockerfile-dev; docker run -it -v $(pwd)/src:/opt/radiale/src:ro -v $(pwd)/config/:/opt/radiale/config/:ro radiale-dev
