param(
    [string[]]$Browsers = @('chrome', 'firefox', 'MicrosoftEdge')
)

$ErrorActionPreference = 'Stop'
$images = @{
    chrome = 'selenium/standalone-chrome:latest'
    firefox = 'selenium/standalone-firefox:latest'
    MicrosoftEdge = 'selenium/standalone-edge:latest'
}
$ports = @{ chrome = 4544; firefox = 4545; MicrosoftEdge = 4546 }

foreach ($browser in $Browsers) {
    $name = 'karate-bidi-' + $browser.ToLower()
    $port = $ports[$browser]
    docker run -d --rm --name $name --shm-size=2g -p "${port}:4444" `
        -e SE_NODE_MAX_SESSIONS=2 -e SE_NODE_OVERRIDE_MAX_SESSIONS=true $images[$browser] | Out-Null
    try {
        $deadline = (Get-Date).AddMinutes(2)
        do {
            Start-Sleep -Seconds 1
            try { $ready = (Invoke-RestMethod "http://localhost:$port/status").value.ready } catch { $ready = $false }
        } until ($ready -or (Get-Date) -gt $deadline)
        if (-not $ready) { throw "Selenium $browser did not become ready" }
        mvn "-Dmaven.repo.local=.m2repo" -pl karate-core -am `
            "-Dtest=BidiGridE2eTest" "-Dsurefire.failIfNoSpecifiedTests=false" `
            "-Dkarate.bidi.gridUrl=http://localhost:$port" `
            "-Dkarate.bidi.browserName=$browser" test
        if ($LASTEXITCODE -ne 0) { throw "BiDi matrix failed for $browser" }
    } finally {
        docker rm -f $name 2>$null | Out-Null
    }
}
