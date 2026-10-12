FROM python:3.12-slim
WORKDIR /app
COPY droidcodology_server.py .
ENV DC_DATA_DIR=/data DC_PORT=8080 PYTHONUNBUFFERED=1
# The data volume must belong to the user the server runs as, or it can't write its database.
RUN mkdir /data && chown nobody /data
VOLUME /data
EXPOSE 8080
USER nobody
CMD ["python3", "droidcodology_server.py"]
