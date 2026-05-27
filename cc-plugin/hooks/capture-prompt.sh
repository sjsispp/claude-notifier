#!/bin/bash
# UserPromptSubmit hook: 缓存最后一条 prompt 到 /tmp
# CC 通过 stdin 传 JSON {session_id, hook_event_name, prompt, ...}
# 失败永不阻塞 CC（exit 0）

set +e
input=$(cat)
session_id=$(echo "$input" | jq -r '.session_id // empty')
prompt=$(echo "$input" | jq -r '.prompt // empty' | head -c 200)

[ -z "$session_id" ] && exit 0

echo "$prompt" > "/tmp/claude-prompt-${session_id}"
exit 0
