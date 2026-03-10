FROM node:20-slim
RUN apt-get update && apt-get install -y git curl
RUN npm install -g @anthropic-ai/claude-code
WORKDIR /workspace
