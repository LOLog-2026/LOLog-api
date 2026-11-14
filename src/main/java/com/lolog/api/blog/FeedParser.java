package com.lolog.api.blog;

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
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

// RSS 2.0과 Atom을 같은 칸으로 읽는다(texts.md 4장 "피드 모양과 본문" 대응표).
public final class FeedParser {

	private static final String ATOM = "http://www.w3.org/2005/Atom";
	private static final String CONTENT = "http://purl.org/rss/1.0/modules/content/";

	private FeedParser() {
	}

	// link는 블로그 첫 화면 주소(RSS channel의 link, Atom feed의 link), generator는 피드를 만든 프로그램(플랫폼 알아보기용)
	public record Feed(String title, String link, String generator, List<Item> items) {
	}

	// 본문 칸은 HTML 그대로다. 카드로 만들 때 toText로 벗긴다.
	// RSS는 description과 content:encoded, Atom은 summary를 description에, content를 contentEncoded에 담는다.
	public record Item(String guid, String title, String link, String description, String contentEncoded,
			OffsetDateTime publishedAt, List<String> categories) {

		// content:encoded가 있으면 그것, 없으면 description(texts.md 4장, 정함 11-14)
		public String body() {
			return contentEncoded != null ? contentEncoded : description;
		}

	}

	// RSS·Atom이 아니면 null
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

	private static Feed rss(org.w3c.dom.Element channel) {
		List<Item> items = new ArrayList<>();
		for (org.w3c.dom.Element item : children(channel, "item")) {
			List<String> categories = new ArrayList<>();
			for (org.w3c.dom.Element c : children(item, "category")) {
				add(categories, c.getTextContent());
			}
			String link = text(item, "link");
			String guid = text(item, "guid");
			items.add(new Item(guid != null ? guid : link, text(item, "title"), link, text(item, "description"),
					contentEncoded(item), rfc1123(text(item, "pubDate")), categories));
		}
		return new Feed(text(channel, "title"), text(channel, "link"), text(channel, "generator"), items);
	}

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
			items.add(new Item(id != null ? id : link, text(entry, "title"), link, text(entry, "summary"),
					text(entry, "content"), iso(published != null ? published : text(entry, "updated")), categories));
		}
		return new Feed(text(feed, "title"), atomLink(feed), text(feed, "generator"), items);
	}

	// RSS 확장 칸 content:encoded(글 전체 HTML). 접두어 이름은 피드마다 달라서 네임스페이스로 찾는다.
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

	// rel이 없거나 alternate인 link의 href
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

	// HTML 본문을 글로 바꾼다. 문단·줄바꿈은 줄바꿈으로 남기고, 태그와 스크립트는 뺀다.
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

	private static void add(List<String> categories, String value) {
		String trimmed = value == null ? "" : value.strip();
		if (!trimmed.isEmpty() && !categories.contains(trimmed)) {
			categories.add(trimmed);
		}
	}

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

	private static String text(org.w3c.dom.Element parent, String name) {
		org.w3c.dom.Element child = child(parent, name);
		return child == null ? null : blankToNull(child.getTextContent());
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static org.w3c.dom.Element child(org.w3c.dom.Element parent, String name) {
		List<org.w3c.dom.Element> found = children(parent, name);
		return found.isEmpty() ? null : found.getFirst();
	}

	// 바로 아래 자식 중 이름이 같은 것(네임스페이스 접두어는 보지 않음. content:encoded 같은 확장 칸과 헷갈리지 않게 접두어 없는 것만)
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
