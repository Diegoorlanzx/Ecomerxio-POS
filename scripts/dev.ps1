# Arranca en esta ventana la base de datos de desarrollo o el servidor de TiendaPOSS.
#   dev.ps1 -Que db       PostgreSQL embebido en el puerto 55434 (los datos se conservan entre reinicios)
#   dev.ps1 -Que backend  API en el puerto 8090 (compila primero si hay cambios)
param([ValidateSet('db', 'backend')][string]$Que)

$ErrorActionPreference = 'Stop'
$backend = Join-Path $PSScriptRoot '..\backend' | Resolve-Path
Set-Location $backend
$mvn = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11-bin\*\apache-maven-3.9.11\bin\mvn.cmd" | Select-Object -First 1

if (-not (Test-Path 'target\cp.txt') -or -not (Test-Path 'target\test-classes\com\tiendaposs\DevDbRunner.class')) {
  Write-Host 'Preparando el proyecto por primera vez (puede tardar unos minutos)...'
  & $mvn.FullName -q test-compile dependency:build-classpath "-Dmdep.outputFile=target/cp.txt" "-Dmdep.includeScope=test"
} elseif ($Que -eq 'backend') {
  Write-Host 'Compilando cambios...'
  & $mvn.FullName -q -o compile
}

$cp = Get-Content 'target\cp.txt' -Raw
if ($Que -eq 'db') {
  & java -cp "target\test-classes;target\classes;$cp" com.tiendaposs.DevDbRunner
} else {
  & java -cp "target\classes;$cp" com.tiendaposs.TiendaPossApplication
}
