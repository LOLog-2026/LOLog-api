package com.lolog.api.blog;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 블로그 주소·RSS를 받아 온다(texts.md 4장 "주소와 보안").
// 사용자가 넣은 주소로 서버가 내부 주소(Redis, DB 등)를 대신 부르지 않게(SSRF) 내부 IP는 거절하고,
// 리다이렉트를 따라가지 않고 직접 하나씩 다시 확인한다.
@Component
public class BlogFetcher {

	static final int MAX_REDIRECTS = 5;
	// 피드 하나의 크기 한도. 넘으면 받지 않는다.
	static final int MAX_BYTES = 3 * 1024 * 1024;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final boolean allowPrivateHosts;
	private final HttpClient client = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(5))
		.build();

	// 테스트에서만 true로 켠다(내 컴퓨터에 띄운 가짜 블로그를 부르려고). 배포에서는 늘 false다.
	public BlogFetcher(@Value("${lolog.blog.allow-private-hosts:false}") boolean allowPrivateHosts) {
		this.allowPrivateHosts = allowPrivateHosts;
	}

	// 받은 본문과 리다이렉트를 따라간 뒤의 최종 주소
	public record Fetched(URI uri, byte[] body, String contentType) {
	}

	// 받지 못하면(내부 주소, 연결 실패, 200이 아님, 너무 큼) FetchException
	public Fetched get(String url) {
		URI uri = parse(url);
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			check(uri);
			HttpResponse<InputStream> response;
			try {
				response = client.send(HttpRequest.newBuilder(uri)
					.timeout(TIMEOUT)
					.header("User-Agent", "LOLog-RSS/1.0")
					.header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.9, */*;q=0.8")
					.GET()
					.build(), HttpResponse.BodyHandlers.ofInputStream());
			}
			catch (IOException e) {
				throw new FetchException("주소에 연결하지 못했습니다.");
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new FetchException("주소에 연결하지 못했습니다.");
			}
			int status = response.statusCode();
			if (status >= 300 && status < 400) {
				close(response);
				String location = response.headers().firstValue("Location")
					.orElseThrow(() -> new FetchException("리다이렉트 주소가 없습니다."));
				uri = parse(uri.resolve(location.strip()).toString());
				continue;
			}
			if (status != 200) {
				close(response);
				throw new FetchException("주소가 " + status + "로 답했습니다.");
			}
			return new Fetched(uri, read(response), response.headers().firstValue("Content-Type").orElse(""));
		}
		throw new FetchException("리다이렉트가 너무 많습니다.");
	}

	static URI parse(String url) {
		try {
			URI uri = new URI(url);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
				throw new FetchException("http·https 주소가 아닙니다.");
			}
			return uri;
		}
		catch (java.net.URISyntaxException e) {
			throw new FetchException("주소 모양이 틀렸습니다.");
		}
	}

	// 호스트가 가리키는 IP가 하나라도 내부 주소면 거절한다.
	private void check(URI uri) {
		if (allowPrivateHosts) {
			return;
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(uri.getHost());
		}
		catch (UnknownHostException e) {
			throw new FetchException("주소를 찾지 못했습니다.");
		}
		for (InetAddress address : addresses) {
			if (isInternal(address)) {
				throw new FetchException("내부 주소는 부를 수 없습니다.", true);
			}
		}
	}

	// 루프백·사설·링크 로컬·멀티캐스트·0.0.0.0/8·CGNAT(100.64/10)·IPv6 ULA(fc00::/7)
	static boolean isInternal(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] b = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = b[0] & 0xff;
			int second = b[1] & 0xff;
			return first == 0 || (first == 100 && second >= 64 && second <= 127) || first >= 240;
		}
		if (address instanceof Inet6Address) {
			return (b[0] & 0xfe) == 0xfc;
		}
		return false;
	}

	private static byte[] read(HttpResponse<InputStream> response) {
		try (InputStream in = response.body()) {
			byte[] bytes = in.readNBytes(MAX_BYTES + 1);
			if (bytes.length > MAX_BYTES) {
				throw new FetchException("받은 내용이 너무 큽니다.");
			}
			return bytes;
		}
		catch (IOException e) {
			throw new FetchException("내용을 받지 못했습니다.");
		}
	}

	private static void close(HttpResponse<InputStream> response) {
		try {
			response.body().close();
		}
		catch (IOException ignored) {
			// 닫지 못해도 다음 요청과 상관없다.
		}
	}

	// 블로그 주소·피드를 받지 못함. 앱에는 400 INVALID_BLOG로 답한다(texts.md 6장).
	public static class FetchException extends RuntimeException {

		private final boolean internal;

		public FetchException(String message) {
			this(message, false);
		}

		public FetchException(String message, boolean internal) {
			super(message);
			this.internal = internal;
		}

		public boolean isInternal() {
			return internal;
		}

	}

}
