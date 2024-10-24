package hudson.remoting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

import java.io.FileInputStream;
import java.io.IOException;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public class LauncherTest {

    @Test
    public void loadDom_Standard() throws IOException, SAXException, ParserConfigurationException {
        // An example of a standard, regular JNLP file.
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/test.jnlp");
        Document document = Launcher.loadDom(jnlpFile);
        Element documentElement = document.getDocumentElement();
        assertThat(documentElement.getNodeName(), is("jnlp"));
        assertThat(documentElement.getChildNodes().getLength(), is(9));
    }

    @Test
    public void loadDom_Lol() throws IOException {
        // A JNLP containing the Billion Laughs DTD
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/lol.jnlp");
        shouldFailWithDoctype(jnlpFile);
    }

    @Test
    public void loadDom_XxeFile() throws IOException {
        // A JNLP containing an file-type XXE
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/xxe_file.jnlp");
        shouldFailWithDoctype(jnlpFile);
    }

    @Test
    public void loadDom_XxeHttp() throws IOException {
        // A JNLP containing an http-type XXE
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/xxe_http.jnlp");
        shouldFailWithDoctype(jnlpFile);
    }

    @Test
    public void loadDom_EmbeddedDoctype() throws IOException {
        // A JNLP containing an embedded doctype
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/embedded_doctype.jnlp");
        shouldFailWithDoctype(jnlpFile);
    }

    private void shouldFailWithDoctype(FileInputStream jnlpFile) {
        final SAXParseException spe = assertThrows(
                "Dom loading should have failed.", SAXParseException.class, () -> Launcher.loadDom(jnlpFile));
        assertThat(spe.getMessage(), containsString("\"http://apache.org/xml/features/disallow-doctype-decl\""));
    }
}
