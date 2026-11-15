Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-PgTool([string]$Name) {
    if ($env:PG_BIN) {
        $candidate = Join-Path $env:PG_BIN ($Name + '.exe')
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "PostgreSQL tool $Name not found. Set PG_BIN to the PostgreSQL bin directory."
}

function Assert-DatabaseName([string]$Name) {
    if ($Name -notmatch '^[a-zA-Z_][a-zA-Z0-9_]{0,62}$') {
        throw 'Database name must contain only ASCII letters, numbers and underscores, and start with a letter or underscore.'
    }
}

function Invoke-Pg([string]$Tool, [string[]]$Arguments) {
    & (Get-PgTool $Tool) @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed (exit code $LASTEXITCODE)." }
}

function Read-BackupManifest([string]$ManifestPath) {
    $manifestFile = Get-Item -LiteralPath $ManifestPath
    $manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.format -ne 'edujournal-pg-custom-v1' -or
        $manifest.dump -notmatch '^edujournal-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{32}\.dump$') {
        throw 'Invalid EduJournal backup manifest.'
    }
    $dump = Join-Path $manifestFile.DirectoryName $manifest.dump
    if ((Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash -ne $manifest.sha256) {
        throw 'Backup checksum mismatch. Restore cancelled.'
    }
    return [pscustomobject]@{ Manifest=$manifest; Dump=$dump }
}
