# BOUNCING BALL A00 PLAYER V36

Clean cloud-build-ready Android Player project.

## Included
- Ball control
- Double jump
- Speed booster ring
- High-jump ring
- Star ring
- Hidden gifts
- Lives and level progression
- Server-authoritative SCORE display
- Backend wallet synchronization
- Player menu: sound, music, rewards, spin, wallet/withdraw information, backend settings
- GitHub Actions APK build workflow

## Backend contract used by the prototype
POST /api/game/action
- playerId
- action
- actionId

GET /api/wallet?playerId=...

The client never submits a SCORE amount. The server must calculate and return SCORE.

## Real-money safety
Withdrawals are disabled in this Player build. A production system must add authenticated server-side wallet transactions, database transactions, anti-fraud controls, eligibility/minimum-withdrawal rules, audit logs, HTTPS, and compliant payout processing.

## Build
GitHub Actions builds `app-debug.apk` and publishes it as the workflow artifact `BOUNCING-BALL-A00-PLAYER-V36-APK`.

No Android Studio is required on the user's PC for this cloud-build route.
