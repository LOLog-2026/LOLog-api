package com.lolog.api.domain.blog.service;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

// RSS 2.0과 Atom을 같은 칸으로 읽는다(texts.md 4장 "피드 모양과 본문" 대응표).
public final class FeedParser {

	private static final String ATOM = "http://www.w3.org/2005/Atom";
	private static final String CONTENT = "http://purl.org/rss/1.0/modules/content/";

	private FeedParser() {
	}

	/**
	 * link는 블로그 첫 화면 주소(RSS channel의 link, Atom feed의 link), generator는 피드를 만든 프로그램(플랫폼 알아보기용)
	 *
	 * @param title 블로그 이름. 없으면 null
	 * @param link 블로그 첫 화면 주소. 없으면 null
	 * @param generator 피드를 만든 프로그램. 없으면 null
	 * @param items 피드의 글(피드에 나온 순서)
	 */
	public record Feed(String title, String link, String generator, List<Item> items) {
	}

	/**
	 * 본문 칸은 HTML 그대로다. 카드로 만들 때 toText로 벗긴다.
	 * RSS는 description과 content:encoded, Atom은 summary를 description에, content를 contentEncoded에 담는다.
	 *
	 * @param guid 글 구분 값(RSS guid, Atom id). 없으면 link
	 * @param title 글 제목. 없으면 null
	 * @param link 글 주소. 없으면 null
	 * @param description RSS description 또는 Atom summary(HTML). 없으면 null
	 * @param contentEncoded RSS content:encoded 또는 Atom content(HTML). 없으면 null
	 * @param publishedAt 글을 쓴 시각. 없거나 읽지 못하면 null
	 * @param categories 글의 카테고리(RSS category, Atom category의 term)
	 */
	public record Item(String guid, String title, String link, String description, String contentEncoded,
			OffsetDateTime publishedAt, List<String> categories) {

		// content:encoded가 있으면 그것, 없으면 description(texts.md 4장, 정함 11-14)
		public String body() {
			return contentEncoded != null ? contentEncoded : description;
		}

	}

	/**
	 * RSS·Atom이 아니면 null
	 *
	 * @param xml 받은 피드 바이트
	 * @return 읽은 피드. RSS·Atom이 아니면 null
	 */
	public static Feed parse(byte[] xml) {
		Document doc;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			// 외부 엔티티를 읽지 않는다(XXE). DOCTYPE이 있는 문서는 받지 않는다.
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setErrorHandler(null);
			doc = builder.parse(new ByteArrayInputStream(xml));
		}
		catch (Exception e) {
			return null;
		}
		org.w3c.dom.Element root = doc.getDocumentElement();
		String name = root.getLocalName() == null ? root.getNodeName() : root.getLocalName();
		if ("rss".equals(name)) {
			org.w3c.dom.Element channel = child(root, "channel");
			return channel == null ? null : rss(channel);
		}
		if ("feed".equals(name) && ATOM.equals(root.getNamespaceURI())) {
			return atom(root);
		}
		return null;
	}

	/**
	 * RSS 2.0 channel을 Feed로 읽는다.
	 *
	 * @param channel RSS의 channel 칸
	 * @return 읽은 피드
	 */
	private static Feed rss(org.w3c.dom.Element channel) {
		List<Item> items = new ArrayList<>();
		for (org.w3c.dom.Element item : children(channel, "item")) {
			List<String> categories = new ArrayList<>();
			for (org.w3c.dom.Element c : children(item, "category")) {
				add(categories, c.getTextContent());
			}
			String link = text(item, "link");
			String guid = text(item, "guid");
			items.add(new Item(guid != null ? guid : link, title(item), link, text(item, "description"),
					contentEncoded(item), rfc1123(text(item, "pubDate")), categories));
		}
		return new Feed(title(channel), text(channel, "link"), text(channel, "generator"), items);
	}

	/**
	 * Atom feed를 Feed로 읽는다.
	 *
	 * @param feed Atom의 feed 칸
	 * @return 읽은 피드
	 */
	private static Feed atom(org.w3c.dom.Element feed) {
		List<Item> items = new ArrayList<>();
		for (org.w3c.dom.Element entry : children(feed, "entry")) {
			List<String> categories = new ArrayList<>();
			for (org.w3c.dom.Element c : children(entry, "category")) {
				add(categories, c.getAttribute("term"));
			}
			String link = atomLink(entry);
			String id = text(entry, "id");
			String published = text(entry, "published");
			items.add(new Item(id != null ? id : link, title(entry), link, text(entry, "summary"),
					text(entry, "content"), iso(published != null ? published : text(entry, "updated")), categories));
		}
		return new Feed(title(feed), atomLink(feed), text(feed, "generator"), items);
	}

	/**
	 * RSS 확장 칸 content:encoded(글 전체 HTML). 접두어 이름은 피드마다 달라서 네임스페이스로 찾는다.
	 *
	 * @param item RSS의 item 칸
	 * @return content:encoded 글자. 없거나 비면 null
	 */
	private static String contentEncoded(org.w3c.dom.Element item) {
		NodeList nodes = item.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof org.w3c.dom.Element e && CONTENT.equals(e.getNamespaceURI())
					&& "encoded".equals(e.getLocalName())) {
				return blankToNull(e.getTextContent());
			}
		}
		return null;
	}

	/**
	 * rel이 없거나 alternate인 link의 href
	 *
	 * @param entry link를 찾을 Atom feed 또는 entry 칸
	 * @return 찾은 href. 그런 link가 없으면 처음 나온 link의 href, 그것도 없으면 null
	 */
	private static String atomLink(org.w3c.dom.Element entry) {
		String any = null;
		for (org.w3c.dom.Element link : children(entry, "link")) {
			String href = blankToNull(link.getAttribute("href"));
			String rel = link.getAttribute("rel");
			if (href != null && (rel.isEmpty() || "alternate".equals(rel))) {
				return href;
			}
			if (any == null) {
				any = href;
			}
		}
		return any;
	}

	/**
	 * HTML 본문을 글로 바꾼다. 문단·줄바꿈은 줄바꿈으로 남기고, 태그와 스크립트는 뺀다.
	 *
	 * @param html 본문 HTML. null이면 빈 글자를 돌려준다
	 * @return 태그를 뺀 글
	 */
	public static String toText(String html) {
		if (html == null) {
			return "";
		}
		org.jsoup.nodes.Document doc = Jsoup.parse(html);
		doc.select("script, style, noscript, iframe").remove();
		StringBuilder out = new StringBuilder();
		append(doc.body(), out);
		return out.toString()
			.replace(' ', ' ')
			.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
			.replaceAll(" *\n *", "\n")
			.replaceAll("\n{3,}", "\n\n")
			.strip();
	}

	/**
	 * 노드를 돌며 글자를 모으고 블록 태그 앞뒤에 줄바꿈을 넣는다.
	 *
	 * @param node 글자를 모을 노드
	 * @param out 글자를 이어 붙일 곳
	 */
	private static void append(Node node, StringBuilder out) {
		for (Node child : node.childNodes()) {
			if (child instanceof TextNode text) {
				out.append(text.text());
			}
			else if (child instanceof Element element) {
				String tag = element.normalName();
				if (tag.equals("br")) {
					out.append('\n');
					continue;
				}
				boolean block = element.isBlock();
				if (block) {
					out.append('\n');
				}
				append(element, out);
				if (block) {
					out.append('\n');
				}
			}
		}
	}

	/**
	 * 비어 있지 않고 아직 없는 카테고리만 더한다.
	 *
	 * @param categories 카테고리를 더할 목록
	 * @param value 더할 카테고리 이름. null 가능
	 */
	private static void add(List<String> categories, String value) {
		String trimmed = value == null ? "" : value.strip();
		if (!trimmed.isEmpty() && !categories.contains(trimmed)) {
			categories.add(trimmed);
		}
	}

	/**
	 * RSS 날짜(RFC 1123)를 읽는다. 안 되면 ISO로 읽어 본다.
	 *
	 * @param value pubDate 글자. null 가능
	 * @return 읽은 시각. 읽지 못하면 null
	 */
	private static OffsetDateTime rfc1123(String value) {
		if (value == null) {
			return null;
		}
		// 요일이 날짜와 안 맞는 피드가 있어 요일은 빼고 읽는다.
		String withoutDay = value.strip().replaceFirst("^[A-Za-z]{3},\\s*", "");
		try {
			return ZonedDateTime.parse(withoutDay, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH))
				.toOffsetDateTime();
		}
		catch (DateTimeParseException e) {
			// 형식이 틀린 날짜는 없는 것으로 본다(카드 날짜는 가져온 시각이 된다).
			return iso(value);
		}
	}

	/**
	 * ISO 8601 날짜를 읽는다. 틀리면 null.
	 *
	 * @param value 날짜 글자. null 가능
	 * @return 읽은 시각. 틀리면 null
	 */
	private static OffsetDateTime iso(String value) {
		if (value == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value.strip());
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * 글 제목·블로그 이름. 티스토리처럼 HTML 글자를 한 번 더 감싸 보내는 피드(&amp;lsquo;)가 있어서,
	 * XML을 푼 뒤 남은 HTML 글자(&lsquo;, &nbsp; 등)를 한 번 더 풀고 줄 없는 공백(nbsp)은 보통 공백으로 바꾼다.
	 *
	 * @param parent title 칸을 찾을 칸(channel, item, feed, entry)
	 * @return 제목. 없거나 비면 null
	 */
	static String title(org.w3c.dom.Element parent) {
		String raw = text(parent, "title");
		if (raw == null || raw.indexOf('&') < 0) {
			return raw;
		}
		return blankToNull(Parser.unescapeEntities(raw, false).replace('\u00a0', ' '));
	}

	/**
	 * 자식 칸의 글자를 읽는다. 없거나 비면 null.
	 *
	 * @param parent 찾을 칸
	 * @param name 자식 칸 이름
	 * @return 앞뒤 공백을 뺀 글자. 없거나 비면 null
	 */
	private static String text(org.w3c.dom.Element parent, String name) {
		org.w3c.dom.Element child = child(parent, name);
		return child == null ? null : blankToNull(child.getTextContent());
	}

	/**
	 * 빈 글자는 null로, 아니면 앞뒤 공백을 뺀다.
	 *
	 * @param value 다듬을 글자. null 가능
	 * @return 다듬은 글자. 비었으면 null
	 */
	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	/**
	 * 이름이 같은 첫 자식 칸. 없으면 null.
	 *
	 * @param parent 찾을 칸
	 * @param name 자식 칸 이름
	 * @return 첫 자식 칸. 없으면 null
	 */
	private static org.w3c.dom.Element child(org.w3c.dom.Element parent, String name) {
		List<org.w3c.dom.Element> found = children(parent, name);
		return found.isEmpty() ? null : found.getFirst();
	}

	/**
	 * 바로 아래 자식 중 이름이 같은 것(네임스페이스 접두어는 보지 않음. content:encoded 같은 확장 칸과 헷갈리지 않게 접두어 없는 것만)
	 *
	 * @param parent 찾을 칸
	 * @param name 자식 칸 이름
	 * @return 접두어 없이 이름이 같은 자식 칸
	 */
	private static List<org.w3c.dom.Element> children(org.w3c.dom.Element parent, String name) {
		List<org.w3c.dom.Element> result = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof org.w3c.dom.Element e) {
				String local = e.getLocalName() == null ? e.getNodeName() : e.getLocalName();
				boolean plain = e.getPrefix() == null;
				if (local.equals(name) && plain) {
					result.add(e);
				}
			}
		}
		return result;
	}

}
