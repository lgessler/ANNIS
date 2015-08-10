/*
 * Copyright 2013 Corpuslinguistic working group Humboldt University Berlin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package annis.gui.docbrowser;

import annis.gui.SearchUI;
import annis.libgui.Helper;
import annis.libgui.PollControl;
import annis.model.Annotation;
import annis.service.objects.CorpusConfig;
import annis.service.objects.DocumentBrowserConfig;
import annis.service.objects.Visualizer;
import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import com.sun.jersey.api.client.WebResource;
import com.vaadin.data.Container;
import com.vaadin.data.Item;
import com.vaadin.data.Property;
import com.vaadin.data.util.ObjectProperty;
import com.vaadin.data.util.filter.SimpleStringFilter;
import com.vaadin.event.FieldEvents;
import static com.vaadin.server.Sizeable.SIZE_UNDEFINED;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Panel;
import com.vaadin.ui.ProgressBar;
import com.vaadin.ui.TextField;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.themes.ChameleonTheme;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Benjamin Weißenfels <b.pixeldrama@gmail.com>
 */
public class DocBrowserPanel extends Panel
{

  private final SearchUI ui;

  private VerticalLayout layout;

  // the name of the corpus from which the documents are fetched
  private String corpus;

  private DocBrowserTable table;

  private Logger log = LoggerFactory.getLogger(DocBrowserPanel.class);

  private CorpusConfig corpusConfig;

  final ProgressBar progress;
  
  private final static Escaper urlPathEscape = UrlEscapers.urlPathSegmentEscaper();
  
  /**
   * Normally get the page size from annis-service.properties for the paging
   * component. If something went wrong this value or the amount of documents
   * within the corpus is used: min(pageSize, amountOf(documents))
   */
  private final int PAGE_SIZE = 20;

  private DocBrowserPanel(SearchUI ui, String corpus)
  {
    this.ui = ui;
    this.corpus = corpus;

    // init layout 
    layout = new VerticalLayout();
    setContent(layout);
    layout.setSizeFull();
    layout.addStyleName(ChameleonTheme.PANEL_BORDERLESS);

    setSizeFull();

    progress = new ProgressBar();
    progress.setIndeterminate(true);
    progress.setSizeFull();
    
  }

  @Override
  public void attach()
  {
    super.attach();

    // start fetching table only if not done yet.
    if (table == null)
    {
      layout.addComponent(progress);
      layout.setComponentAlignment(progress, Alignment.MIDDLE_CENTER);
      PollControl.runInBackground(100, ui, new LoadingDocs());
    }
  }

  public DocumentBrowserConfig getDocBrowserConfig()
  {
    return Helper.getDocBrowserConfig(corpus);
  }

  /**
   * Initiated the {@link DocBrowserPanel} and put the main tab navigation.
   *
   * @param ui The main application class of the gui.
   * @param corpus The corpus, for which the doc browser is initiated.
   * @return A new wrapper panel for a doc browser. Make sure, that this is not
   * done several times.
   */
  public static DocBrowserPanel initDocBrowserPanel(SearchUI ui, String corpus)
  {
    return new DocBrowserPanel(ui, corpus);
  }

  public void openVis(String doc, Visualizer config, Button btn)
  {
    ui.getDocBrowserController().openDocVis(corpus, doc, config, btn);
  }

  private class LoadingDocs implements Runnable
  {

    @Override
    public void run()
    {

      WebResource res = Helper.getAnnisWebResource();
      final List<Annotation> docs = res.path("meta/docnames/"
        + urlPathEscape.escape(corpus)).
        get(new Helper.AnnotationListType());

      ui.access(new Runnable()
      {
        @Override
        public void run()
        {
          table = DocBrowserTable.getDocBrowserTable(DocBrowserPanel.this);
          layout.removeComponent(progress);
          
          TextField txtFilter = new TextField();
          txtFilter.setWidth("100%");
          txtFilter.setInputPrompt("Filter documents by name");
          txtFilter.setImmediate(true);
          txtFilter.setTextChangeTimeout(500);
          txtFilter.addTextChangeListener(new FieldEvents.TextChangeListener()
          {

            @Override
            public void textChange(FieldEvents.TextChangeEvent event)
            {
              if (table != null)
              {
                table.setContainerFilter(new SimpleStringFilter(
                  DocBrowserTable.PROP_DOC_NAME, event.getText(), true,
                  false));
              }
            }
          });
          
          layout.addComponent(txtFilter);
          layout.addComponent(table);
          layout.setExpandRatio(table, 1.0f);
          

          table.setDocNames(docs);
        }
      });
    }
  }

  public String getCorpus()
  {
    return corpus;
  }

}
