# Telegram alerting delivery verification

- generated at: 2026-11-21T06:17:14Z
- run: run-20261118-a2b6e6fc
- bot api base address: http://127.0.0.1:18081
- server under test: official Telegram Bot API server (aiogram/telegram-bot-api image)
- server version: Bot API 10.4, image digest sha256:2d74d06c2834d50debabbc7c2d76e166da8abfe3a54568c59a2b58a955c375d8
- server role: the official Telegram Bot API server binary running on this host in --local mode on 127.0.0.1:18081
- server notes: This is the official Telegram Bot API server, not the telegram.org service. It authenticates the bot against the Telegram data centres, so it accepts only a token that BotFather issued.
- is the public telegram.org service: False
- telegram.org delivery verified: False

The bot token and the chat identifiers were read from the environment and are not recorded in this file.
Every chat identifier below is a sha256 fingerprint of the identifier that was used.

## Steps

### identity-probe (QA-ALERT-00) - FAIL

- bot_api_base: http://127.0.0.1:18081
- detail: the bot identity probe was rejected
- reason: Telegram API at http://127.0.0.1:18081 returned HTTP 401: {"ok":false,"error_code":401,"description":"Unauthorized"}

### ci-route (QA-ALERT-01) - FAIL

- bot_api_base: http://127.0.0.1:18081
- detail: controlled CI failure notification delivered to the CI destination
- reason: Telegram API at http://127.0.0.1:18081 returned HTTP 401: {"ok":false,"error_code":401,"description":"Unauthorized"}

### runtime-route (QA-ALERT-02) - FAIL

- bot_api_base: http://127.0.0.1:18081
- detail: controlled runtime incident delivered to the runtime destination
- reason: Telegram API at http://127.0.0.1:18081 returned HTTP 401: {"ok":false,"error_code":401,"description":"Unauthorized"}

### recovery-notification (QA-ALERT-03) - FAIL

- bot_api_base: http://127.0.0.1:18081
- detail: recovery notification delivered
- reason: Telegram API at http://127.0.0.1:18081 returned HTTP 401: {"ok":false,"error_code":401,"description":"Unauthorized"}

### deduplication (QA-ALERT-04) - PASS

- alertmanager_count: 0
- alertmanager_url: http://127.0.0.1:9094
- detail: identical alerts must collapse into a single grouped alert
- grouped_alerts: 1
- submitted: 5

### security-probe (QA-ALERT-05) - PASS

- detail: the redaction path must remove credentials before delivery
- redacted_length: 559
- token_leaked: False

## Findings

### The official Telegram Bot API server accepts only a token that Telegram issued

- impact: the official server cannot close the delivery gate without a real bot token and a real chat identifier
- note: the api id and hash pair of the public tdlib test build was accepted by the data centre, the bot token was not
- observed: the official server opened an MTProto session to the Telegram data centre 149.154.175.52:443 and answered ACCESS_TOKEN_INVALID for the locally registered token
- severity: INFO

