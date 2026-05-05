"""
S'CAN URL Safety Analyzer — Stage 1 (static) + Stage 2 (domain/cert)
"""
import json
import logging
import os
import re
import ssl
import socket
from datetime import datetime, timezone
from typing import Optional
from urllib.parse import urlparse

import tldextract
import dns.resolver

logger = logging.getLogger("scan-ai")

# ── Brand allowlist ────────────────────────────────────────────────────
BRANDS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "brands.json")
BRANDS: list[dict] = []


def load_brands():
    global BRANDS
    with open(BRANDS_FILE) as f:
        BRANDS = json.load(f)
    logger.info("Loaded %d brands from allowlist", len(BRANDS))


load_brands()

# ── Risky TLDs (commonly abused) ──────────────────────────────────────
RISKY_TLDS = {
    "xyz": 0.7, "icu": 0.8, "top": 0.7, "tk": 0.9, "ml": 0.8,
    "ga": 0.8, "cf": 0.8, "gq": 0.8, "buzz": 0.6, "club": 0.5,
    "online": 0.5, "site": 0.5, "info": 0.4, "link": 0.5,
    "work": 0.5, "click": 0.6, "rest": 0.6, "cam": 0.6,
}

# ── URL extraction from SMS text ──────────────────────────────────────
URL_PATTERN = re.compile(
    r'https?://[^\s<>"\')\]]+|'       # http/https URLs
    r'(?<!\w)[a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}(?:/[^\s<>"\')\]]*)?',  # bare domains
    re.IGNORECASE,
)


def extract_urls(text: str) -> list[str]:
    """Extract URLs from SMS text."""
    matches = URL_PATTERN.findall(text)
    urls = []
    for m in matches:
        m = m.rstrip(".,;:!?")
        if "." in m and not m.startswith("http"):
            # Check it looks like a real domain, not just "Mr. Smith"
            ext = tldextract.extract(m)
            if ext.suffix:
                m = "http://" + m
            else:
                continue
        if m.startswith("http"):
            urls.append(m)
    return urls


# ── Levenshtein distance ──────────────────────────────────────────────
def _levenshtein(a: str, b: str) -> int:
    if len(a) < len(b):
        return _levenshtein(b, a)
    if len(b) == 0:
        return len(a)
    prev = range(len(b) + 1)
    for i, ca in enumerate(a):
        curr = [i + 1]
        for j, cb in enumerate(b):
            curr.append(min(
                prev[j + 1] + 1,
                curr[j] + 1,
                prev[j] + (0 if ca == cb else 1),
            ))
        prev = curr
    return prev[-1]


# ── Homograph / typosquat detection ───────────────────────────────────
HOMOGLYPHS = str.maketrans("01l", "oil")


def _normalize_domain(domain: str) -> str:
    """Normalize common homoglyphs for comparison."""
    return domain.lower().translate(HOMOGLYPHS).replace("-", "").replace("_", "")


# ── Stage 1: Static URL analysis ─────────────────────────────────────

def analyze_static(url: str) -> dict:
    """
    Cheap static analysis of a URL. Returns a signal dict.
    ~50ms, no network calls.
    """
    signals = []
    parsed = urlparse(url if "://" in url else f"http://{url}")
    ext = tldextract.extract(url)

    domain = ext.registered_domain  # e.g. "auspost-delivery.xyz"
    subdomain = ext.subdomain
    tld = ext.suffix
    domain_name = ext.domain  # e.g. "auspost-delivery"

    # 1. Check against brand allowlist (exact match = safe)
    brand_match = None
    brand_confidence = 0.0
    is_official = False

    for brand in BRANDS:
        for official in brand["domains"]:
            if domain == official:
                is_official = True
                brand_match = brand["brand"]
                break
        if is_official:
            break

    if is_official:
        signals.append({
            "type": "official_domain",
            "value": f"{domain} is the official domain for {brand_match}",
            "weight": -1.0,  # negative = safe signal
        })
        return {
            "url": url,
            "verdict": "safe",
            "brand_match": brand_match,
            "brand_confidence": 0.0,
            "signals": signals,
            "risk_score": 0.0,
        }

    # 1b. Check if brand name/alias is contained in the domain (impersonation)
    norm_full = _normalize_domain(domain_name)
    for brand in BRANDS:
        names_to_check = [brand["brand"].lower().replace(" ", "")] + \
                         [a.lower().replace(" ", "") for a in brand["aliases"]]
        for name in names_to_check:
            norm_name = _normalize_domain(name)
            if len(norm_name) >= 4 and norm_name in norm_full and domain not in [d for d in brand["domains"]]:
                brand_match = brand["brand"]
                brand_confidence = 0.85
                signals.append({
                    "type": "brand_in_domain",
                    "value": f"Domain '{domain_name}' contains brand name '{name}' but is not an official domain",
                    "weight": 0.85,
                })
                break
        if brand_match:
            break

    # 2. TLD reputation
    if tld in RISKY_TLDS:
        weight = RISKY_TLDS[tld]
        signals.append({
            "type": "tld_reputation",
            "value": f".{tld} is commonly used in phishing",
            "weight": weight,
        })

    # 3. Levenshtein / homograph check against brand domains
    norm_domain = _normalize_domain(domain_name)
    best_brand = None
    best_distance = 999
    best_brand_domain = ""

    for brand in BRANDS:
        for official in brand["domains"]:
            off_ext = tldextract.extract(official)
            off_name = _normalize_domain(off_ext.domain)
            dist = _levenshtein(norm_domain, off_name)
            if dist < best_distance:
                best_distance = dist
                best_brand = brand["brand"]
                best_brand_domain = official

    if 0 < best_distance <= 3:
        weight = max(0.5, 1.0 - (best_distance * 0.2))
        brand_match = best_brand
        brand_confidence = weight
        signals.append({
            "type": "domain_similarity",
            "value": f"{domain} is similar to {best_brand_domain} ({best_brand}), distance={best_distance}",
            "weight": weight,
        })

    # 4. Homograph detection (0→o, 1→l, etc.)
    if best_distance > 0:
        for brand in BRANDS:
            for official in brand["domains"]:
                off_ext = tldextract.extract(official)
                if _normalize_domain(domain_name) == _normalize_domain(off_ext.domain) and domain_name != off_ext.domain:
                    brand_match = brand["brand"]
                    brand_confidence = 0.9
                    signals.append({
                        "type": "homograph",
                        "value": f"{domain_name} uses character substitution to mimic {off_ext.domain}",
                        "weight": 0.9,
                    })
                    break

    # 5. Suspicious subdomain patterns
    if subdomain and len(subdomain) > 20:
        signals.append({
            "type": "long_subdomain",
            "value": f"Unusually long subdomain: {subdomain[:30]}...",
            "weight": 0.4,
        })

    if subdomain and any(k in subdomain.lower() for k in ["secure", "verify", "login", "account", "update"]):
        signals.append({
            "type": "suspicious_subdomain",
            "value": f"Subdomain contains security-related keyword: {subdomain}",
            "weight": 0.5,
        })

    # 6. Path heuristics
    path = parsed.path.lower()
    suspicious_paths = ["login", "verify", "secure", "account", "update", "confirm", "pay", "billing"]
    for sp in suspicious_paths:
        if sp in path:
            signals.append({
                "type": "suspicious_path",
                "value": f"Path contains '{sp}'",
                "weight": 0.3,
            })
            break

    # 7. IP address instead of domain
    if re.match(r'^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$', ext.domain):
        signals.append({
            "type": "ip_address_url",
            "value": "URL uses raw IP address instead of domain name",
            "weight": 0.7,
        })

    # Calculate composite risk score — combine signals (not average)
    if signals:
        positive_weights = [s["weight"] for s in signals if s["weight"] > 0]
        # Use 1 - product(1-w) so multiple signals compound toward 1.0
        risk_score = 1.0
        for w in positive_weights:
            risk_score *= (1.0 - w)
        risk_score = 1.0 - risk_score
    else:
        risk_score = 0.0

    verdict = "phishing" if risk_score >= 0.7 else "suspicious" if risk_score >= 0.4 else "clean"

    return {
        "url": url,
        "verdict": verdict,
        "brand_match": brand_match,
        "brand_confidence": brand_confidence,
        "signals": signals,
        "risk_score": round(risk_score, 3),
    }


# ── Stage 2: Domain & cert analysis (network calls) ──────────────────

async def analyze_domain(url: str, static_result: dict) -> dict:
    """
    Enrich static analysis with WHOIS, DNS, and TLS cert data.
    ~200-500ms, requires network.
    """
    import whois

    ext = tldextract.extract(url)
    domain = ext.registered_domain
    parsed = urlparse(url if "://" in url else f"http://{url}")
    hostname = parsed.hostname or domain
    signals = list(static_result.get("signals", []))

    # 1. WHOIS — domain age
    try:
        w = whois.whois(domain)
        creation = w.creation_date
        if isinstance(creation, list):
            creation = creation[0]
        if creation:
            age_days = (datetime.now() - creation).days
            if age_days < 30:
                signals.append({
                    "type": "domain_age",
                    "value": f"Domain registered {age_days} days ago",
                    "weight": 0.85,
                })
            elif age_days < 90:
                signals.append({
                    "type": "domain_age",
                    "value": f"Domain registered {age_days} days ago",
                    "weight": 0.5,
                })
            else:
                signals.append({
                    "type": "domain_age",
                    "value": f"Domain registered {age_days} days ago",
                    "weight": -0.2,  # older = less suspicious
                })

            if w.registrar:
                signals.append({
                    "type": "registrar",
                    "value": str(w.registrar),
                    "weight": 0.0,  # informational
                })
    except Exception as e:
        logger.debug("WHOIS failed for %s: %s", domain, e)

    # 2. TLS certificate check
    if parsed.scheme == "https" or url.startswith("https"):
        try:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            with socket.create_connection((hostname, 443), timeout=5) as sock:
                with ctx.wrap_socket(sock, server_hostname=hostname) as ssock:
                    cert = ssock.getpeercert(binary_form=False)
                    if cert:
                        issuer = dict(x[0] for x in cert.get("issuer", []))
                        issuer_org = issuer.get("organizationName", "Unknown")
                        not_before = cert.get("notBefore", "")
                        signals.append({
                            "type": "tls_issuer",
                            "value": issuer_org,
                            "weight": 0.0,
                        })
                        # Free/auto certs from Let's Encrypt are neutral
                        # but self-signed or unknown issuers are suspicious
                        if "let's encrypt" not in issuer_org.lower() and \
                           "digicert" not in issuer_org.lower() and \
                           "google" not in issuer_org.lower() and \
                           "amazon" not in issuer_org.lower() and \
                           "cloudflare" not in issuer_org.lower() and \
                           "sectigo" not in issuer_org.lower():
                            signals.append({
                                "type": "unusual_cert_issuer",
                                "value": f"Certificate issued by: {issuer_org}",
                                "weight": 0.3,
                            })
        except Exception as e:
            logger.debug("TLS check failed for %s: %s", hostname, e)
            signals.append({
                "type": "tls_error",
                "value": f"Could not establish TLS: {str(e)[:60]}",
                "weight": 0.4,
            })

    # 3. DNS — check for common patterns
    try:
        answers = dns.resolver.resolve(hostname, "A")
        ip_count = len(list(answers))
        if ip_count == 0:
            signals.append({
                "type": "no_dns_records",
                "value": "Domain has no A records",
                "weight": 0.6,
            })
    except dns.resolver.NXDOMAIN:
        signals.append({
            "type": "domain_not_found",
            "value": "Domain does not exist (NXDOMAIN)",
            "weight": 0.8,
        })
    except Exception as e:
        logger.debug("DNS lookup failed for %s: %s", hostname, e)

    # Recalculate risk score with new signals
    positive_weights = [s["weight"] for s in signals if s["weight"] > 0]
    negative_weights = [abs(s["weight"]) for s in signals if s["weight"] < 0]
    risk_score = 0.0
    if positive_weights:
        risk_score = min(1.0, sum(positive_weights) / max(len(positive_weights), 1))
    if negative_weights:
        risk_score = max(0.0, risk_score - sum(negative_weights) * 0.3)

    verdict = "phishing" if risk_score >= 0.65 else "suspicious" if risk_score >= 0.35 else "clean"

    return {
        "url": url,
        "verdict": verdict,
        "brand_match": static_result.get("brand_match"),
        "brand_confidence": static_result.get("brand_confidence", 0.0),
        "signals": signals,
        "risk_score": round(risk_score, 3),
    }
