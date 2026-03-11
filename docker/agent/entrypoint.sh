#!/bin/bash
# Watch for instructions and forward them to the AI agent
INSTRUCTIONS_FILE="/workspace/.nucleus-instructions.txt"
touch "$INSTRUCTIONS_FILE"
tail -f "$INSTRUCTIONS_FILE" | while read -r instruction; do
    echo "[nucleus] Received instruction: $instruction"
    claude --dangerously-skip-permissions -p "$instruction"
done
