# Build from the repo root: docker build -f deploy/Dockerfile -t jot .
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /jot .

FROM alpine:3
RUN adduser -D -H jot && mkdir /data && chown jot /data
COPY --from=build /jot /usr/local/bin/jot
USER jot
ENV JOT_DATA=/data JOT_ADDR=:8080
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["jot"]
CMD ["serve"]
