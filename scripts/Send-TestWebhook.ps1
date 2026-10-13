param(
    [string]$Url = 'http://localhost:8080/line/webhook',
    [string]$BodyPath = (Join-Path $PSScriptRoot 'webhook-sample.json'),
    [switch]$InvalidSignature
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:LINE_CHANNEL_SECRET)) {
    throw 'Set LINE_CHANNEL_SECRET in the current terminal first.'
}
$payloadPath = (Resolve-Path -LiteralPath $BodyPath).Path
$body = [System.IO.File]::ReadAllBytes($payloadPath)
$mac = [System.Security.Cryptography.HMACSHA256]::new(
    [System.Text.Encoding]::UTF8.GetBytes($env:LINE_CHANNEL_SECRET))
try {
    $signature = [Convert]::ToBase64String($mac.ComputeHash($body))
} finally {
    $mac.Dispose()
}
if ($InvalidSignature) {
    $signature = [Convert]::ToBase64String([byte[]]::new(32))
}
# curl sends the exact bytes that were signed, including any final newline.
curl.exe --silent --show-error --include --request POST $Url `
    --header 'Content-Type: application/json' `
    --header "X-Line-Signature: $signature" `
    --data-binary "@$payloadPath"
exit $LASTEXITCODE
