//! Make's link helpers (QRCore `Make/`, SPEC §8): smaller codes and tracking tags. Pure text in,
//! text out; only `http` and `https` links are touched.

use crate::data::TrackingParam;
use crate::text as t;
use crate::url::{self, PublicSuffixList, WebAddress, url_text};
use std::sync::OnceLock;

/// An http(s) link cut into the pieces the compactor rewrites, each kept exactly as written.
struct Parts {
    scheme: String,
    /// `user:pw@` including the `@`, or empty.
    userinfo: String,
    host: String,
    host_ascii: String,
    /// `:8080` including the colon, or empty.
    port: String,
    rest: String,
}

fn empty_psl() -> &'static PublicSuffixList {
    static PSL: OnceLock<PublicSuffixList> = OnceLock::new();
    PSL.get_or_init(|| PublicSuffixList::new(""))
}

impl Parts {
    /// None unless `link` is an absolute http(s) link written with exactly `//` after the scheme.
    fn new(link: &str) -> Option<Parts> {
        let c = url_text::clean(link);
        let (name, after) = url::split_scheme(&c)?;
        if (name != "http" && name != "https") || !t::has_prefix(after, "//") {
            return None;
        }
        let address = WebAddress::parse(&c, empty_psl())?;
        let body = t::drop_first(after, 2);
        if t::first(body).is_some_and(|g| t::ch_eq(g, '/') || t::ch_eq(g, '\\')) {
            return None;
        }
        let end =
            t::find(body, |g| t::ch_eq(g, '/') || t::ch_eq(g, '\\') || t::ch_eq(g, '?') || t::ch_eq(g, '#')).unwrap_or(body.len());
        let authority = &body[..end];
        let mut host_port = authority;
        let mut user = "";
        if let Some(at) = t::rfind_ch(authority, '@') {
            let after_at = at + t::char_len_at(authority, at);
            user = &authority[..after_at];
            host_port = &authority[after_at..];
        }
        let (host, port) = if t::has_prefix(host_port, "[") {
            let close = t::find_ch(host_port, ']')?;
            let e = close + t::char_len_at(host_port, close);
            (&host_port[..e], &host_port[e..])
        } else if let Some(colon) = t::rfind_ch(host_port, ':') {
            (&host_port[..colon], &host_port[colon..])
        } else {
            (host_port, "")
        };
        Some(Parts {
            scheme: t::prefix_while(&c, |g| !t::ch_eq(g, ':')).to_string(),
            userinfo: user.to_string(),
            host: host.to_string(),
            host_ascii: address.link.host_ascii,
            port: port.to_string(),
            rest: body[end..].to_string(),
        })
    }

    /// The port is exactly the default for the scheme (leading zeros included).
    fn has_default_port(&self) -> bool {
        let n = t::count(&self.port);
        if !t::has_prefix(&self.port, ":") || n <= 1 || n > 6 {
            return false;
        }
        let digits = t::drop_first(&self.port, 1);
        if !t::graphemes(digits).all(|g| t::ch_is_ascii(g) && t::ch_is_number(g)) {
            return false;
        }
        let Some(v) = t::parse_int(digits) else { return false };
        v == if t::lower(&self.scheme) == "https" { 443 } else { 80 }
    }

    fn joined(&self, scheme: &str, host: &str, port: &str, rest: &str) -> String {
        format!("{scheme}://{}{host}{port}{rest}", self.userinfo)
    }
}

/// Drops `:443` on https and `:80` on http, and a lone trailing `/` when nothing follows it.
pub fn trimmed(link: &str) -> String {
    let Some(p) = Parts::new(link) else { return link.to_string() };
    let port = if p.has_default_port() { "" } else { p.port.as_str() };
    let rest = if p.rest == "/" { "" } else { p.rest.as_str() };
    p.joined(&p.scheme, &p.host, port, rest)
}

/// The scheme and the host (ASCII form) in capitals; the rest byte for byte.
pub fn capitalized(link: &str) -> Option<String> {
    let p = Parts::new(link)?;
    Some(p.joined(&t::upper(&p.scheme), &t::upper(&p.host_ascii), &p.port, &p.rest))
}

/// `trimmed` then `capitalized`. Parses to the same normalized link as the input.
pub fn compacted(link: &str) -> Option<String> {
    capitalized(&trimmed(link))
}

struct Query {
    before: String,
    params: Vec<String>,
    fragment: String,
}

fn query(link: &str) -> Option<Query> {
    let p = Parts::new(link)?;
    let rest = &p.rest;
    let hash = t::find_ch(rest, '#').unwrap_or(rest.len());
    let before_fragment = &rest[..hash];
    let mark = t::find_ch(before_fragment, '?')?;
    let query_text = &before_fragment[mark + t::char_len_at(before_fragment, mark)..];
    if query_text.is_empty() {
        return None;
    }
    Some(Query {
        before: p.joined(&p.scheme, &p.host, &p.port, &rest[..mark]),
        params: t::split_ch(query_text, '&', false).into_iter().map(String::from).collect(),
        fragment: rest[hash..].to_string(),
    })
}

fn name_of(param: &str) -> Option<&str> {
    let n = t::prefix_while(param, |g| !t::ch_eq(g, '='));
    (!n.is_empty()).then_some(n)
}

/// The query parameters (names as written) that match `list`, in order of appearance.
pub fn tracking_parameters(link: &str, list: &[TrackingParam]) -> Vec<String> {
    let Some(q) = query(link) else { return vec![] };
    q.params.iter().filter_map(|p| name_of(p).filter(|n| list.iter().any(|tp| tp.matches(n))).map(String::from)).collect()
}

/// The link without those parameters (and without a `?` left empty), or None when it carries none.
pub fn removing_tracking(link: &str, list: &[TrackingParam]) -> Option<String> {
    let q = query(link)?;
    let kept: Vec<&String> =
        q.params.iter().filter(|p| name_of(p).is_none_or(|n| !list.iter().any(|tp| tp.matches(n)))).collect();
    if kept.len() >= q.params.len() {
        return None;
    }
    let new_query = if kept.is_empty() {
        String::new()
    } else {
        format!("?{}", kept.iter().map(|s| s.as_str()).collect::<Vec<_>>().join("&"))
    };
    Some(q.before + &new_query + &q.fragment)
}

#[uniffi::export]
pub fn link_trimmed(link: String) -> String {
    trimmed(&link)
}

#[uniffi::export]
pub fn link_capitalized(link: String) -> Option<String> {
    capitalized(&link)
}

#[uniffi::export]
pub fn link_compacted(link: String) -> Option<String> {
    compacted(&link)
}

#[uniffi::export]
pub fn link_tracking_parameters(link: String, list: Vec<TrackingParam>) -> Vec<String> {
    tracking_parameters(&link, &list)
}

#[uniffi::export]
pub fn link_removing_tracking(link: String, list: Vec<TrackingParam>) -> Option<String> {
    removing_tracking(&link, &list)
}
