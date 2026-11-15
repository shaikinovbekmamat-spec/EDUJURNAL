[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ManifestPath,
    [Parameter(Mandatory=$true)][string]$TargetDatabase,
    [string]$MaintenanceDatabase = 'postgres'
)
. (Join-Path $PSScriptRoot 'backup-common.ps1')
Assert-DatabaseName $TargetDatabase
Assert-DatabaseName $MaintenanceDatabase
if ($TargetDatabase -in @('postgres','template0','template1')) { throw 'Choose a new application database.' }
$backup = Read-BackupManifest $ManifestPath
if ($TargetDatabase -eq $backup.Manifest.database) { throw 'Restore must use a new database name, different from the source.' }
$existing = Invoke-Pg 'psql' @('--no-password','--no-psqlrc','--tuples-only','--no-align','--set','ON_ERROR_STOP=1','--dbname',$MaintenanceDatabase,'--command',"select 1 from pg_database where datname='$TargetDatabase'")
if ($existing -contains '1') { throw 'Target database already exists. Existing databases are never overwritten.' }
Invoke-Pg 'pg_restore' @('--list',$backup.Dump) | Out-Null
Invoke-Pg 'createdb' @('--no-password','--maintenance-db',$MaintenanceDatabase,'--template=template0',$TargetDatabase)
# On failure all restored objects roll back; the newly created empty database is retained for diagnosis.
Invoke-Pg 'pg_restore' @('--no-password','--exit-on-error','--single-transaction','--no-owner','--no-privileges','--dbname',$TargetDatabase,$backup.Dump)
Write-Output "Restored to $TargetDatabase. Verify records, file downloads and permissions before changing DB_URL."
