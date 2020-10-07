package io.lippia.reporter.cucumber4.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cucumber.api.PickleStepTestStep;
import cucumber.api.Result;
import cucumber.api.TestCase;
import cucumber.api.event.ConcurrentEventListener;
import cucumber.api.event.EventHandler;
import cucumber.api.event.EventPublisher;
import cucumber.api.event.TestCaseFinished;
import cucumber.api.event.TestCaseStarted;
import cucumber.api.event.TestRunFinished;
import cucumber.api.event.TestSourceRead;
import cucumber.api.event.TestStepFinished;
import cucumber.api.event.TestStepStarted;
import cucumber.api.formatter.StrictAware;
import gherkin.ast.DataTable;
import gherkin.ast.DocString;
import gherkin.ast.Examples;
import gherkin.ast.Feature;
import gherkin.ast.Node;
import gherkin.ast.ScenarioDefinition;
import gherkin.ast.ScenarioOutline;
import gherkin.ast.Step;
import gherkin.ast.TableCell;
import gherkin.ast.TableRow;
import gherkin.ast.Tag;
import gherkin.pickles.Argument;
import gherkin.pickles.PickleCell;
import gherkin.pickles.PickleRow;
import gherkin.pickles.PickleString;
import gherkin.pickles.PickleTable;
import gherkin.pickles.PickleTag;
import io.lippia.reportServer.api.client.LippiaReportServerApiClient;
import io.lippia.reportServer.api.model.InitializeResponseDTO;
import io.lippia.reportServer.api.model.LogDTO;
import io.lippia.reportServer.api.model.StatusDTO;
import io.lippia.reportServer.api.model.TestDTO;
import io.lippia.reporter.service.MarkupHelper;
import io.lippia.reporter.service.PropertiesService;

/**
 * A port of Cucumber-JVM (MIT licensed) HtmlFormatter for Extent Framework 
 * Original source: https://github.com/cucumber/cucumber-jvm/blob/master/core/src/main/java/cucumber/runtime/formatter/HTMLFormatter.java
 *
 */
public abstract class ReportServerApiAdapter implements ConcurrentEventListener, StrictAware {

    static InitializeResponseDTO report;
    
    static {
    	report = LippiaReportServerApiClient.initialize();
    }

    static Map<String, TestDTO> featureMap = new ConcurrentHashMap<>();
    static ThreadLocal<TestDTO> featureTestThreadLocal = new InheritableThreadLocal<>();
    static Map<String, TestDTO> scenarioOutlineMap = new ConcurrentHashMap<>();
    static ThreadLocal<TestDTO> scenarioOutlineThreadLocal = new InheritableThreadLocal<>();
    static ThreadLocal<TestDTO> scenarioThreadLocal = new InheritableThreadLocal<>();
    
    static ThreadLocal<Map<String, String>> extraInfoThreadLocal = new InheritableThreadLocal<>();

    boolean strict = false;
    
    private final TestSourcesModel testSources = new TestSourcesModel();

    private ThreadLocal<String> currentFeatureFile = new ThreadLocal<>();
    private ThreadLocal<ScenarioOutline> currentScenarioOutline = new InheritableThreadLocal<>();
    
    public ReportServerApiAdapter(String arg) {
		super();
	}

	private EventHandler<TestSourceRead> testSourceReadHandler = new EventHandler<TestSourceRead>() {
        @Override
        public void receive(TestSourceRead event) {
            handleTestSourceRead(event);
        }
    };
    private EventHandler<TestCaseStarted> caseStartedHandler= new EventHandler<TestCaseStarted>() {
        @Override
        public void receive(TestCaseStarted event) {
            handleTestCaseStarted(event);
        }
    };
    
    private EventHandler<TestCaseFinished> caseFinishHandler= new EventHandler<TestCaseFinished>() {
        @Override
        public void receive(TestCaseFinished event) {
            handleTestCaseFinished(event);
        }
    };
    
    private EventHandler<TestStepStarted> stepStartedHandler = new EventHandler<TestStepStarted>() {
        @Override
        public void receive(TestStepStarted event) {
            handleTestStepStarted(event);
        }
    };
    private EventHandler<TestStepFinished> stepFinishedHandler = new EventHandler<TestStepFinished>() {
        @Override
        public void receive(TestStepFinished event) {
            handleTestStepFinished(event);
        }
    };
    private EventHandler<TestRunFinished> runFinishedHandler = new EventHandler<TestRunFinished>() {
        @Override
        public void receive(TestRunFinished event) {
            LippiaReportServerApiClient.finishReport(report);
        }
    };

	@Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestSourceRead.class, testSourceReadHandler);
        publisher.registerHandlerFor(TestCaseStarted.class, caseStartedHandler);
        publisher.registerHandlerFor(TestCaseFinished.class, caseFinishHandler);
        publisher.registerHandlerFor(TestStepStarted.class, stepStartedHandler);
        publisher.registerHandlerFor(TestStepFinished.class, stepFinishedHandler);
        publisher.registerHandlerFor(TestRunFinished.class, runFinishedHandler);
    }
    
    @Override
    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    private void handleTestSourceRead(TestSourceRead event) {
        testSources.addTestSourceReadEvent(event.uri, event);
    }

    private synchronized void handleTestCaseStarted(TestCaseStarted event) {
        handleStartOfFeature(event.testCase);
        handleScenarioOutline(event.testCase);
        createTestCase(event.testCase);
        if (testSources.hasBackground(currentFeatureFile.get(), event.testCase.getLine())) { 
            // background
        }
    }
    
    private synchronized void handleTestCaseFinished(TestCaseFinished event) {
    	// Extra info on scenarioFinish
    	if (extraInfoThreadLocal.get() != null && !extraInfoThreadLocal.get().isEmpty()) {
		  LogDTO extraInfoLogDTO = new LogDTO(scenarioThreadLocal.get(), "Extra Info");
		  extraInfoLogDTO.updateResult(StatusDTO.INFO, getExtraInfoAsHtml());
		  LippiaReportServerApiClient.log(extraInfoLogDTO);
		}
    }

    synchronized void handleTestStepStarted(TestStepStarted event) {

    }

    synchronized void handleTestStepFinished(TestStepFinished event) {
    	
        if (event.testStep instanceof PickleStepTestStep) {
            PickleStepTestStep testStep = (PickleStepTestStep) event.testStep;
            
            LogDTO stepLog = createTestStep(testStep);
            if(stepLog.getStatus() == null) 
            	stepLog = updateResult(stepLog, event.result);
            
            LippiaReportServerApiClient.log(stepLog);
        }
    }
    
    synchronized LogDTO updateResult(LogDTO stepLog, Result result) {
    	StatusDTO status = StatusDTO.fromLowerCaseName(result.getStatus().name());
    			
    	switch (result.getStatus()) {
	        case FAILED:
				String image = getBase64Image();
				if(image!=null) {
					stepLog.updateResult(status, result.getErrorMessage(), getBase64Image());
					break;
				}
	        	break;
	        default:
	        	stepLog.updateResult(status, result.getErrorMessage());
	            break;
    	}
    	return stepLog;
    }

    private synchronized void handleStartOfFeature(TestCase testCase) {
        if (currentFeatureFile == null || !currentFeatureFile.equals(testCase.getUri())) {
            currentFeatureFile.set(testCase.getUri());
            createFeature(testCase);
        }
    }

    private synchronized void createFeature(TestCase testCase) {
        Feature feature = testSources.getFeature(testCase.getUri());
        if (feature != null) {
            if (featureMap.containsKey(feature.getName())) {
                featureTestThreadLocal.set(featureMap.get(feature.getName()));
                return;
            }            
            if (featureTestThreadLocal.get() != null && featureTestThreadLocal.get().getName().equals(feature.getName())) {
                return;
            }
            TestDTO t = new TestDTO();
            
            t.setType("feature");
            t.setName(feature.getName());
            t.setDescription(feature.getDescription());
            t.setExcecutionIdentifier(report.getExcecutionIdentifier());
            
        // author assignation by systemProperty
            String author = (String)PropertiesService.getProperty("extent.author");
            if(author != null && !author.isEmpty()) {
            	t.setAuthor(author);
            }
            
         // device assignation by systemProperty
            String device = (String)PropertiesService.getProperty("extent.device");
            if(device != null && !device.isEmpty()) {
            	t.setDevice(device);
            }
            
            List<String> tagList = createTagsList(feature.getTags());
            t.setTags(tagList);
            
			TestDTO testCreated = LippiaReportServerApiClient.create(t);
			
			featureTestThreadLocal.set(testCreated);
            featureMap.put(testCreated.getName(), testCreated);

        }
    }

    private List<String> createTagsList(List<Tag> tags) {
        List<String> tagList = new ArrayList<>();
        for (Tag tag : tags) {
            tagList.add(tag.getName());
        }
        return tagList;
    }

    private synchronized void handleScenarioOutline(TestCase testCase) {
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile.get(), testCase.getLine());
        if (TestSourcesModel.isScenarioOutlineScenario(astNode)) {
            ScenarioOutline scenarioOutline = (ScenarioOutline)TestSourcesModel.getScenarioDefinition(astNode);
            if (currentScenarioOutline.get() == null || !currentScenarioOutline.get().getName().equals(scenarioOutline.getName())) {
                scenarioOutlineThreadLocal.set(null);
                createScenarioOutline(scenarioOutline);
                currentScenarioOutline.set(scenarioOutline);
                addOutlineStepsToReport(scenarioOutline);
	            
                Examples examples = (Examples)astNode.parent.node;
                createExamples(examples);

                TestDTO newScenarioOutline = LippiaReportServerApiClient.create(scenarioOutlineThreadLocal.get());
                scenarioOutlineThreadLocal.set(newScenarioOutline);
            }
            
        } else {
            scenarioOutlineThreadLocal.set(null);
            currentScenarioOutline.set(null);
        }
    }

	private synchronized void createScenarioOutline(ScenarioOutline scenarioOutline) {
        if (scenarioOutlineMap.containsKey(scenarioOutline.getName())) {
            scenarioOutlineThreadLocal.set(scenarioOutlineMap.get(scenarioOutline.getName()));
            return;
        }
        if (scenarioOutlineThreadLocal.get() == null) {
        	TestDTO feature = featureTestThreadLocal.get();
        	
        	TestDTO t = new TestDTO();
            
            t.setType("scenario-outline");
            t.setName(scenarioOutline.getName());
            t.setDescription(scenarioOutline.getDescription());
            t.setTestParentIdentifier(feature.getTestIdentifier());
            t.setExcecutionIdentifier(report.getExcecutionIdentifier());

            
            List<String> featureTags = feature.getTags();
            
            scenarioOutline.getTags()
            	.stream()
            	.map(x -> x.getName())
            	.filter(x -> !featureTags.contains(x))
            	.forEach(t::assignCategory);
          
			scenarioOutlineThreadLocal.set(t);
			scenarioOutlineMap.put(scenarioOutline.getName(), t);
        }
    }

    private synchronized void addOutlineStepsToReport(ScenarioOutline scenarioOutline) {
        for (Step step : scenarioOutline.getSteps()) {
            if (step.getArgument() != null) {
                Node argument = step.getArgument();
                if (argument instanceof DocString) {
                    createDocStringMap((DocString)argument);
                } else if (argument instanceof DataTable) {
                    
                }
            }
        }
    }

    private Map<String, Object> createDocStringMap(DocString docString) {
        Map<String, Object> docStringMap = new HashMap<String, Object>();
        docStringMap.put("value", docString.getContent());
        return docStringMap;
    }

    private void createExamples(Examples examples) {
        List<TableRow> rows = new ArrayList<>();
        rows.add(examples.getTableHeader());
        rows.addAll(examples.getTableBody());
        String[][] data = getTable(rows);
        String markup = MarkupHelper.createTable(data).getMarkup();
        if (examples.getName() != null && !examples.getName().isEmpty()) {
            markup = examples.getName() + markup;
        }
        markup = scenarioOutlineThreadLocal.get().getDescription() + markup;
        scenarioOutlineThreadLocal.get().setDescription(markup);
    }
    
    private String[][] getTable(List<TableRow> rows) {
        String data[][] = null;
        int rowSize = rows.size();
        for (int i = 0; i < rowSize; i++) {
            TableRow row = rows.get(i);
            List<TableCell> cells = row.getCells();
            int cellSize = cells.size();
            if (data == null) {
                data = new String[rowSize][cellSize];
            }
            for (int j = 0; j < cellSize; j++) {
                data[i][j] = cells.get(j).getValue();
            }
        }
        return data;
    }

    synchronized void createTestCase(TestCase testCase) {
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile.get(), testCase.getLine());
        if (astNode != null) {
            ScenarioDefinition scenarioDefinition = TestSourcesModel.getScenarioDefinition(astNode);
            TestDTO parent = scenarioOutlineThreadLocal.get() != null ? scenarioOutlineThreadLocal.get() : featureTestThreadLocal.get();
            
        	TestDTO t = new TestDTO();
            t.setType("scenario");
            t.setName(testCase.getName());
            t.setDescription(scenarioDefinition.getDescription());
            t.setTestParentIdentifier(parent.getTestIdentifier());
            t.setExcecutionIdentifier(report.getExcecutionIdentifier());

            if (!testCase.getTags().isEmpty()) {
                testCase.getTags()
    	    		.stream()
    	    		.map(PickleTag::getName)
    	    		.forEach(t::assignCategory);
            }
            
			TestDTO testCreated = LippiaReportServerApiClient.create(t);
			scenarioThreadLocal.set(testCreated);          
        }
        
    }

    synchronized LogDTO createTestStep(PickleStepTestStep testStep) {
        String stepName = testStep.getStepText();
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile.get(), testStep.getStepLine());
        LogDTO stepLog = null;
        if (astNode != null) {
            Step step = (Step) astNode.node;
            String name = stepName == null || stepName.isEmpty() 
                    ? step.getText().replace("<", "&lt;").replace(">", "&gt;")
                    : stepName;
                    
                    stepLog = new LogDTO(scenarioThreadLocal.get(), step.getKeyword() + name);        
        }

        	
        if (!testStep.getStepArgument().isEmpty()) {
            Argument argument = testStep.getStepArgument().get(0);
            if (argument instanceof PickleString) {
                createDocStringMap((PickleString)argument);
            } else if (argument instanceof PickleTable) {
                List<PickleRow> rows = ((PickleTable) argument).getRows();
                stepLog.updateResult(StatusDTO.PASSED, MarkupHelper.createTable(getPickleTable(rows)).getMarkup());
                
//                stepTestThreadLocal.get().pass(MarkupHelper.createTable(getPickleTable(rows)).getMarkup());
            }
        }

        
        return stepLog;
    }
    
    private String[][] getPickleTable(List<PickleRow> rows) {
        String data[][] = null;
        int rowSize = rows.size();
        for (int i = 0; i < rowSize; i++) {
            PickleRow row = rows.get(i);
            List<PickleCell> cells = row.getCells();
            int cellSize = cells.size();
            if (data == null) {
                data = new String[rowSize][cellSize];
            }
            for (int j = 0; j < cellSize; j++) {
                data[i][j] = cells.get(j).getValue();
            }
        }
        return data;
    }

    private Map<String, Object> createDocStringMap(PickleString docString) {
        Map<String, Object> docStringMap = new HashMap<String, Object>();
        docStringMap.put("value", docString.getContent());
        return docStringMap;
    }

    
    private String getExtraInfoAsHtml() {
        String template = "<div class=\"ng-binding\"><b>%s: </b>%s</div>";
        String finalHtml = "";

        for (Map.Entry<String, String> entry : extraInfoThreadLocal.get().entrySet()) {
            finalHtml = finalHtml.concat(String.format(template, entry.getKey(), entry.getValue()));
        }
        return finalHtml;
    }


    /**
     * This method can be called from anywhere to add information related to the scenario.
     * The information contained in that map will be logged in after hook.
     *
     * @param title
     * @param description
     */
    public static void addExtraInfo(String title, String description) {
        if (extraInfoThreadLocal.get() == null) {
            extraInfoThreadLocal.set(new HashMap<String, String>());
        }
        extraInfoThreadLocal.get().put(title, description);
    }
    
    

    public abstract String getBase64Image();
}
