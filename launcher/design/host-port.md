# Rust Supervisor

`bscp-host.exe` is a separate portable Rust process. The UI communicates using versioned, bounded JSON over a root-specific Windows named pipe. The current user SID is required both by the pipe ACL and by the client's verification of its server. Remote clients and a second pipe instance are refused. No .NET executable is invoked.

The host keeps independent server/client ownership records, including PID and process creation time. Recovery verifies live process ownership, agent attestation, server control and native profile/world leases before adoption. Public game listeners coexist with authenticated loopback administrative control. The host survives closing the UI and permits its own shutdown only after managed game processes have stopped.

`start`, `connect`, `checkpoint`, `stop`, `backup`, `update`, `rollback`, `report`, `status`, `metrics`, `probe` and `shutdown` are available through RPC. The command-line diagnostic form is `bscp-host.exe --root <root> --rpc <command> --arg key=value`.

The local signing proxy keeps display nicknames separate from the persisted P256 account. It signs exact method, raw URI, timestamp, 40-hex-character nonce and body hash with P1363 ECDSA. Browser-origin requests and redirects are refused. Native sessions are injected by the Java bridge instead of appearing as command-line credentials. Client working directories persist across sessions, while logs, native temporary files and JFR belong to each session.

Metrics return `{state, health, server, client, addresses, metricErrors}`. `health` is the native server snapshot, including players and tick statistics. `server` and `client` contain authenticated bridge JVM samples such as uptime, heap, threads, GC and frame/tick gaps. Missing samples are null. Each bridge read validates owned PID/creation time, session path, attested agent kind/PID, loopback port, private token, response PID and a 1 MiB streamed limit; queries run concurrently with a two-second bound. Session directories have current-user ACLs before private agent properties are written.

The Java bridge remains Java because it instruments JVM game classes. Its small JAR is a game integration component, not a .NET or browser runtime. Production game classes and bootstrap are not rewritten by this Rust port.

Validation includes 11 host tests and an actual isolated server/client lifecycle in `../artifacts/host-lifecycle-qa.json`: signed native login, autojoin, server PID recovery, checkpoint and normal stopping. The first two native test attempts exposed a loading-window hang and an intrusive test observer; the final attempt without that observer connected normally. Combined client crash adoption, NAT traversal and clean Windows deployment remain separate release checks.
