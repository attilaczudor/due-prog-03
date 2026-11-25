package hu.due.rewear.nav;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the documents the tax authority sends back.
 *
 * <p>The parser is locked down before it is used. This XML arrives over the network from outside the
 * application, and an XML parser with its defaults will follow a document type declaration to a file or a URL
 * of the document's choosing. Doctypes are therefore refused outright and every external reference is
 * disabled, so a hostile or merely broken response cannot make the application read files or open connections.
 *
 * <p>Element names are matched by local name, ignoring the namespace prefix, because the interface uses
 * several namespaces and has changed prefixes between revisions.
 */
public final class NavResponses {

    private NavResponses() { }

    /** The outcome of one invoice inside a transaction. */
    public record ProcessingResult(int index, String invoiceStatus, List<Message> messages) {

        public boolean hasError() {
            return messages.stream().anyMatch(Message::isError);
        }

        public boolean hasWarning() {
            return messages.stream().anyMatch(message -> !message.isError());
        }

        /** The messages run together for an operator to read, or an empty string when NAV said nothing. */
        public String messageText() {
            return messages.stream().map(Message::toString).reduce((a, b) -> a + "; " + b).orElse("");
        }
    }

    /** One validation message, technical or business, as NAV worded it. */
    public record Message(String code, String level, String text) {

        public boolean isError() {
            return "ERROR".equalsIgnoreCase(level) || "FATAL".equalsIgnoreCase(level);
        }

        @Override
        public String toString() {
            return (code == null || code.isBlank() ? "" : code + ": ") + (text == null ? "" : text);
        }
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("The XML parser could not be secured", ex);
        } catch (Exception ex) {
            throw new NavException("The reply from NAV was not readable XML", true, null, ex);
        }
    }

    /**
     * Turns an error document into an exception. A {@code GeneralExceptionResponse} is NAV refusing the
     * request itself; a {@code GeneralErrorResponse} carries validation detail alongside the refusal.
     */
    static NavException toException(String xml, int httpStatus) {
        String funcCode = text(xml, "funcCode");
        String errorCode = text(xml, "errorCode");
        String message = text(xml, "message");
        List<Message> validation = messages(parse(xml).getDocumentElement());
        StringBuilder detail = new StringBuilder();
        if (errorCode != null) {
            detail.append(errorCode);
        }
        if (message != null && !message.isBlank()) {
            detail.append(detail.length() == 0 ? "" : " — ").append(message);
        }
        for (Message one : validation) {
            detail.append(detail.length() == 0 ? "" : "; ").append(one);
        }
        if (detail.length() == 0) {
            detail.append("NAV returned HTTP ").append(httpStatus);
        }
        // A server fault or an outage is worth trying again; a refusal on the content is not, because the
        // same bytes will be refused the same way.
        boolean retryable = httpStatus >= 500 || "ERROR".equals(funcCode) && errorCode == null;
        return new NavException(detail.toString(), retryable, errorCode, null);
    }

    static String text(String xml, String localName) {
        Element root = parse(xml).getDocumentElement();
        Element found = firstElement(root, localName);
        return found == null ? null : found.getTextContent().trim();
    }

    static List<ProcessingResult> processingResults(Document document) {
        List<ProcessingResult> results = new ArrayList<>();
        for (Element result : elements(document.getDocumentElement(), "processingResult")) {
            Element index = firstElement(result, "index");
            Element status = firstElement(result, "invoiceStatus");
            results.add(new ProcessingResult(
                    index == null ? 1 : Integer.parseInt(index.getTextContent().trim()),
                    status == null ? "" : status.getTextContent().trim(),
                    messages(result)));
        }
        return results;
    }

    private static List<Message> messages(Element parent) {
        List<Message> messages = new ArrayList<>();
        for (Element validation : elements(parent, "technicalValidationMessages")) {
            messages.add(message(validation, "validationResultCode", "validationErrorCode", "message"));
        }
        for (Element validation : elements(parent, "businessValidationMessages")) {
            messages.add(message(validation, "validationResultCode", "validationErrorCode", "message"));
        }
        return messages;
    }

    private static Message message(Element parent, String levelName, String codeName, String textName) {
        Element level = firstElement(parent, levelName);
        Element code = firstElement(parent, codeName);
        Element text = firstElement(parent, textName);
        return new Message(
                code == null ? null : code.getTextContent().trim(),
                level == null ? "ERROR" : level.getTextContent().trim(),
                text == null ? "" : text.getTextContent().trim());
    }

    static Element firstElement(Element parent, String localName) {
        List<Element> found = elements(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> elements(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        collect(parent, localName, found);
        return found;
    }

    private static void collect(Node node, String localName, List<Element> into) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (localName.equals(child.getLocalName()) || localName.equals(child.getNodeName())) {
                into.add((Element) child);
            } else {
                collect(child, localName, into);
            }
        }
    }
}
