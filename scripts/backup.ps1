[CmdletBinding()]
param(
    [string]$Database = $env:PGDATABASE,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../backups'),
    [ValidateRange(1,3650)][int]$RetentionDays = 14,
    [switch]$Prune
)
. (Join-Path $PSScriptRoot 'backup-common.ps1')
Assert-DatabaseName $Database
$directory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$name = 'edujournal-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N')
$dump = Join-Path $directory ($name + '.dump')
# pg_dump writes binary data directly: no PowerShell redirection or encoding conversion.
Invoke-Pg 'pg_dump' @('--no-password','--format=custom','--file', $dump,'--dbname', $Database)
Invoke-Pg 'pg_restore' @('--list', $dump) | Out-Null
$manifest = [ordered]@{
    format='edujournal-pg-custom-v1'; database=$Database
    createdUtc=[DateTime]::UtcNow.ToString('o'); dump=[IO.Path]::GetFileName($dump)
    sha256=(Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash
    includes='Database schema, rows, sequences, homework files and message attachments'
}
$manifestPath = Join-Path $directory ($name + '.json')
$manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Output "Backup manifest: $manifestPath"

# Retention runs only after a successful backup and only for verified files of this database.
if ($Prune) {
    foreach ($file in Get-ChildItem -LiteralPath $directory -Filter 'edujournal-*.json' -File) {
        try { $old = Read-BackupManifest $file.FullName } catch { Write-Warning "Skipping invalid manifest: $($file.Name)"; continue }
        if ($old.Manifest.database -ne $Database -or [DateTimeOffset]::Parse($old.Manifest.createdUtc) -ge [DateTimeOffset]::UtcNow.AddDays(-$RetentionDays)) { continue }
        foreach ($target in @($old.Dump, $file.FullName)) {
            $resolved = [IO.Path]::GetFullPath($target)
            if ([IO.Path]::GetDirectoryName($resolved) -ne $directory) { throw 'Retention path is outside the backup directory.' }
            Remove-Item -LiteralPath $resolved
        }
    }
}
