FROM python:3.11-slim

WORKDIR /srv/tv-parental
COPY backend /srv/tv-parental/backend
COPY app/src/main/assets/questions.json /srv/tv-parental/app/src/main/assets/questions.json

ENV HOST=0.0.0.0
ENV PORT=8080
ENV TV_PARENTAL_DATA_FILE=/data/state.json
EXPOSE 8080
VOLUME ["/data"]

CMD ["python", "backend/server.py"]
