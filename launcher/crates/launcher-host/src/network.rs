use anyhow::{Result, bail};
use bscp_launcher_core::LocalIdentity;
use sha2::{Digest, Sha256};
use std::{
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{Notify, Semaphore},
};
pub fn endpoint(a: &str) -> Result<reqwest::Url> {
    let raw = if a.contains("://") {
        a.to_owned()
    } else {
        format!("http://{a}")
    };
    let mut u = reqwest::Url::parse(&raw)?;
    if !matches!(u.scheme(), "http" | "https")
        || !u.username().is_empty()
        || u.password().is_some()
        || u.path() != "/"
        || u.query().is_some()
        || u.fragment().is_some()
        || matches!(u.host_str(), Some("0.0.0.0" | "::"))
    {
        bail!("ADDRESS_INVALID: IP or domain and port required")
    }
    if u.port().is_none() {
        u.set_port(Some(29545))
            .map_err(|_| anyhow::anyhow!("port"))?
    }
    Ok(u)
}
pub fn check_ports(base: u16) -> Result<()> {
    if !(1024..=65000).contains(&base) {
        bail!("PORT_INVALID")
    }
    let _a = std::net::TcpListener::bind(("0.0.0.0", base))?;
    let _b = std::net::TcpListener::bind(("0.0.0.0", base + 1))?;
    let _c = std::net::UdpSocket::bind(("0.0.0.0", base + 2))?;
    let _d = std::net::TcpListener::bind(("127.0.0.1", base + 100))?;
    Ok(())
}
pub fn local_addresses() -> Vec<String> {
    // Connecting a UDP socket selects a route without transmitting any datagram.
    let address = std::net::UdpSocket::bind("0.0.0.0:0").ok().and_then(|s| {
        s.connect("192.0.2.1:9").ok()?;
        s.local_addr().ok().map(|a| a.ip().to_string())
    });
    address.into_iter().filter(|a| a != "0.0.0.0").collect()
}
pub struct SignedProxy {
    pub port: u16,
    stop: Arc<Notify>,
}
impl Drop for SignedProxy {
    fn drop(&mut self) {
        self.stop.notify_one();
    }
}
impl SignedProxy {
    pub async fn start(
        remote: reqwest::Url,
        id: LocalIdentity,
        fixed: Option<u16>,
    ) -> Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", fixed.unwrap_or(0))).await?;
        let port = listener.local_addr()?.port();
        let stop = Arc::new(Notify::new());
        let signal = stop.clone();
        let id = Arc::new(id);
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(30))
            .build()?;
        tokio::spawn(async move {
            let cap = Arc::new(Semaphore::new(8));
            loop {
                let accepted = tokio::select! {r=listener.accept()=>r,_=signal.notified()=>break};
                let Ok((mut stream, _)) = accepted else { break };
                let Ok(permit) = cap.clone().try_acquire_owned() else {
                    let _ = stream
                        .write_all(
                            b"HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                        )
                        .await;
                    continue;
                };
                let http = http.clone();
                let id = id.clone();
                let remote = remote.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    let response = tokio::time::timeout(
                        Duration::from_secs(35),
                        forward(&mut stream, &http, &id, &remote),
                    )
                    .await;
                    let (code, ct, body) = match response {
                        Ok(Ok(v)) => v,
                        _ => (
                            502,
                            "application/json".into(),
                            b"{\"error\":\"Backend request failed\"}".to_vec(),
                        ),
                    };
                    let header = format!(
                        "HTTP/1.1 {code} Result\r\nContent-Type: {ct}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        body.len()
                    );
                    let _ = stream.write_all(header.as_bytes()).await;
                    let _ = stream.write_all(&body).await;
                    let _ = stream.shutdown().await;
                });
            }
        });
        Ok(Self { port, stop })
    }
    pub fn url(&self) -> String {
        format!("http://127.0.0.1:{}/", self.port)
    }
    pub async fn session(&self, nickname: &str) -> Result<String> {
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(30))
            .build()?;
        let u = self.url();
        let v: serde_json::Value = http
            .post(format!("{u}session/game"))
            .form(&[("login", nickname)])
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let session = v["session"]
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("SESSION_INVALID"))?
            .to_owned();
        if session.is_empty() || session.len() > 1024 || session.chars().any(char::is_control) {
            bail!("SESSION_INVALID")
        }
        let v: serde_json::Value = http
            .get(format!("{u}characters/list"))
            .query(&[("session", &session)])
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        if v["characters"]
            .as_array()
            .ok_or_else(|| anyhow::anyhow!("CHARACTERS_INVALID"))?
            .is_empty()
        {
            let r = http
                .post(format!("{u}characters/create"))
                .form(&[("session", session.as_str()), ("character", nickname)])
                .send()
                .await?;
            if r.status() == 409 {
                bail!("NICKNAME_TAKEN: choose another nickname for your first character")
            }
            r.error_for_status()?;
        }
        Ok(session)
    }
}
async fn forward(
    stream: &mut TcpStream,
    http: &reqwest::Client,
    id: &LocalIdentity,
    remote: &reqwest::Url,
) -> Result<(u16, String, Vec<u8>)> {
    let mut header = Vec::new();
    while !header.ends_with(b"\r\n\r\n") {
        if header.len() >= 16384 {
            bail!("HEADER_LIMIT")
        }
        header.push(stream.read_u8().await?);
    }
    let text = std::str::from_utf8(&header)?;
    let mut lines = text.split("\r\n");
    let mut first = lines.next().unwrap_or("").split(' ');
    let method = first.next().unwrap_or("");
    let path = first.next().unwrap_or("");
    if !matches!(method, "GET" | "POST")
        || !path.starts_with('/')
        || path.starts_with("//")
        || path.contains(['\r', '\n', '#'])
    {
        bail!("PROXY_REQUEST")
    }
    let mut length = 0;
    let mut ct = "application/x-www-form-urlencoded";
    let mut seen = false;
    for l in lines.filter(|s| !s.is_empty()) {
        let (k, v) = l.split_once(':').ok_or_else(|| anyhow::anyhow!("header"))?;
        match k.to_ascii_lowercase().as_str() {
            "origin" | "sec-fetch-site" | "transfer-encoding" => bail!("PROXY_FORBIDDEN"),
            "content-length" => {
                if seen {
                    bail!("duplicate length")
                }
                seen = true;
                length = v.trim().parse::<usize>()?
            }
            "content-type" => ct = v.trim(),
            _ => {}
        }
    }
    if length > 8192 {
        bail!("PROXY_BODY")
    }
    let mut body = vec![0; length];
    stream.read_exact(&mut body).await?;
    // The connection target belongs to this proxy, never to a browser header or
    // game-supplied field. Keep the existing v1 signature and bind the route by
    // including it in the signed form body / raw query.
    let route=route_parameter(remote)?;
    let mut path=path.to_owned();
    if method=="GET" {
        path.push(if path.contains('?'){'&'}else{'?'});path.push_str(&route);
    }else{
        if !body.is_empty() && !body.ends_with(b"&"){body.push(b'&');}
        body.extend_from_slice(route.as_bytes());
        if body.len()>8192{bail!("PROXY_BODY");}
    }
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)?
        .as_secs()
        .to_string();
    let mut nonce_bytes = [0u8; 20];
    rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut nonce_bytes);
    let nonce = hex::encode(nonce_bytes);
    let message = format!(
        "{method}\n{path}\n{time}\n{nonce}\n{}",
        hex::encode(Sha256::digest(&body))
    );
    let target = format!("{}{path}", remote.as_str().trim_end_matches('/'));
    let mut r = http
        .request(reqwest::Method::from_bytes(method.as_bytes())?, target)
        .header("X-BSCP-Key", &id.public_key)
        .header("X-BSCP-Time", time)
        .header("X-BSCP-Nonce", nonce)
        .header("X-BSCP-Signature", id.sign(&message)?);
    if !body.is_empty() {
        r = r.header("Content-Type", ct).body(body)
    }
    let mut response = r.send().await?;
    let status = response.status().as_u16();
    let ct = response
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("application/json")
        .to_owned();
    if response.content_length().unwrap_or(0) > 4 * 1024 * 1024 {
        bail!("PROXY_LIMIT")
    }
    let mut out = Vec::new();
    while let Some(chunk) = response.chunk().await? {
        if out.len() + chunk.len() > 4 * 1024 * 1024 {
            bail!("PROXY_LIMIT")
        }
        out.extend_from_slice(&chunk)
    }
    Ok((status, ct, out))
}
fn route_parameter(remote:&reqwest::Url)->Result<String> {
    if !matches!(remote.scheme(),"http"|"https") || remote.path()!="/" || remote.query().is_some() || remote.fragment().is_some() || !remote.username().is_empty() || remote.password().is_some() || remote.host_str().is_none(){bail!("ADDRESS_INVALID");}
    let authority=remote.as_str().split_once("://").ok_or_else(||anyhow::anyhow!("ADDRESS_INVALID"))?.1.trim_end_matches('/');
    let mut encoder=reqwest::Url::parse("http://route.invalid/")?;
    encoder.query_pairs_mut().append_pair("__bscp_route",authority);
    Ok(encoder.query().unwrap().into())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reject_unsafe_endpoints() {
        for a in [
            "http://user:pw@host/",
            "http://host/path",
            "http://0.0.0.0/",
            "file:///x",
        ] {
            assert!(endpoint(a).is_err())
        }
        assert_eq!(endpoint("127.0.0.1").unwrap().port(), Some(29545));
    }
    #[test]
    fn route_field_preserves_ipv6_and_encodes_authority_as_one_value() {
        let field=route_parameter(&reqwest::Url::parse("https://[2001:db8::7]:8443/").unwrap()).unwrap();
        let mut url=reqwest::Url::parse("http://fixture.invalid/").unwrap();url.set_query(Some(&field));
        let fields=url.query_pairs().collect::<Vec<_>>();
        assert_eq!(fields.len(),1);assert_eq!(fields[0].0,"__bscp_route");assert_eq!(fields[0].1,"[2001:db8::7]:8443");
        assert!(route_parameter(&reqwest::Url::parse("https://user:pw@host/").unwrap()).is_err());
    }
}
#[cfg(test)]
mod integration_tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    #[tokio::test]
    async fn signing_preserves_raw_url_and_uses_p1363() {
        let root =
            std::env::temp_dir().join(format!("bscp-rust-proxy-test-{}", uuid::Uuid::new_v4()));
        let layout = bscp_launcher_core::Layout::new(&root);
        layout.init().unwrap();
        let id = LocalIdentity::load(&layout).unwrap();
        let public = id.public_key.clone();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = tokio::spawn(async move {
            let (mut s, _) = listener.accept().await.unwrap();
            let mut b = Vec::new();
            while !b.ends_with(b"\r\n\r\n") {
                b.push(s.read_u8().await.unwrap());
            }
            let raw = String::from_utf8(b).unwrap();
            let header = |key: &str| {
                raw.lines()
                    .find_map(|l| {
                        l.split_once(':')
                            .filter(|(k, _)| k.eq_ignore_ascii_case(key))
                            .map(|(_, v)| v.trim().to_owned())
                    })
                    .unwrap()
            };
            let mut body = vec![0; header("content-length").parse::<usize>().unwrap()];
            s.read_exact(&mut body).await.unwrap();
            assert!(raw.starts_with("POST /session/game?x=%2f&x=+ HTTP/1.1"));
            let mut form=reqwest::Url::parse("http://fixture.invalid/").unwrap();
            form.set_query(Some(std::str::from_utf8(&body).unwrap()));
            assert_eq!(form.query_pairs().filter(|(k,_)|k=="__bscp_route").last().unwrap().1,format!("127.0.0.1:{port}"));
            assert_eq!(header("X-BSCP-Nonce").len(), 40);
            assert!(
                header("X-BSCP-Nonce")
                    .bytes()
                    .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
            );
            let msg = format!(
                "POST\n/session/game?x=%2f&x=+\n{}\n{}\n{}",
                header("X-BSCP-Time"),
                header("X-BSCP-Nonce"),
                hex::encode(Sha256::digest(&body))
            );
            assert!(LocalIdentity::verify(
                &public,
                &msg,
                &header("X-BSCP-Signature")
            ));
            s.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"session\":\"s\"}").await.unwrap();
        });
        let proxy = SignedProxy::start(
            reqwest::Url::parse(&format!("http://127.0.0.1:{port}/")).unwrap(),
            id,
            None,
        )
        .await
        .unwrap();
        let r = reqwest::Client::new()
            .post(format!("{}session/game?x=%2f&x=+", proxy.url()))
            .body("login=Player&__bscp_route=attacker.invalid")
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), 200);
        assert_eq!(r.text().await.unwrap(), "{\"session\":\"s\"}");
        server.await.unwrap();
        drop(proxy);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn rejects_browser_origin_without_forwarding() {
        let root =
            std::env::temp_dir().join(format!("bscp-rust-origin-test-{}", uuid::Uuid::new_v4()));
        let l = bscp_launcher_core::Layout::new(&root);
        l.init().unwrap();
        let proxy = SignedProxy::start(
            endpoint("127.0.0.1:9").unwrap(),
            LocalIdentity::load(&l).unwrap(),
            None,
        )
        .await
        .unwrap();
        let r = reqwest::Client::new()
            .get(proxy.url())
            .header("Origin", "https://evil.invalid")
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), 502);
        drop(proxy);
        std::fs::remove_dir_all(root).unwrap();
    }
}
