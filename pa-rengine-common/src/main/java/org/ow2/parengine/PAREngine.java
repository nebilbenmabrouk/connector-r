package org.ow2.parengine;

import org.apache.log4j.Logger;
import org.ow2.proactive.scheduler.common.SchedulerConstants;
import org.ow2.proactive.scheduler.common.task.TaskResult;
import org.ow2.proactive.scheduler.core.properties.PASchedulerProperties;
import org.ow2.proactive.scheduler.task.SchedulerVars;
import org.ow2.proactive.scripting.Script;
import org.ow2.proactive.scripting.helper.progress.ProgressFile;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Abstract R implementation of ScriptEngine.
 *
 * @author Activeeon Team
 */
public abstract class PAREngine extends AbstractScriptEngine {


    public static final String TASK_PROGRESS_MSG = "TaskProgress";
    public static final String ERROR_TAG_BEGIN = "<PARError>";
    public static final String ERROR_TAG_END = "</PARError>";
    /**
     * Base path to local space
     */
    public static final String NODE_DATASPACE_SCRATCHDIR = "node.dataspace.scratchdir";
    /**
     * logger
     */
    protected static final Logger logger = Logger.getLogger(PAREngine.class);
    /**
     * The instance of factory that has created this engine
     */
    protected PAREngineFactory factory;
    protected PARConnection engine;
    /**
     * The task progress from 0 to 100
     */
    protected String taskProgressFile;
    /**
     * The last error message generated by a call to stop() or an error
     */
    protected String lastErrorMessage;
    /**
     * Error builder, in case of multi-line
     */
    private StringBuilder error = new StringBuilder();
    private boolean readError = false;

    protected static boolean isInForkedTask() {
        return "true".equals(System.getProperty(PASchedulerProperties.TASK_FORK.getKey()));
    }

    /**
     * Map java path to R path (as string)
     */
    public static String toRpath(String path) {
        return path.replaceAll("\\\\", "/");
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return this.factory;
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    /**
     * Turn warnings on
     *
     * @param ctx
     */
    protected void enableWarnings(ScriptContext ctx) {
        engine.engineEval("options(warn=1)", ctx);
    }

    /**
     * Retrieve variables map from R and merge them with the java one
     */
    protected void updateJobVariables(Map<String, Serializable> jobVariables, ScriptContext ctx) {
        if (jobVariables == null) {
            return;
        }
        if (engine.engineCast(engine.engineEval("exists(\"" + SchedulerConstants.VARIABLES_BINDING_NAME + "\")", ctx), Boolean.class, ctx)) {
            Object variablesRexp = engine.engineEval(SchedulerConstants.VARIABLES_BINDING_NAME, ctx);
            if (variablesRexp == null) {
                return;
            }
            Map newMap = engine.engineCast(variablesRexp, Map.class, ctx);
            jobVariables.putAll(newMap);
        }
    }

    /**
     * Retrieve variables map from R and merge them with the java one
     */
    protected void updateResultMetadata(Map<String, String> metadata, ScriptContext ctx) {
        if (metadata == null) {
            return;
        }
        if (engine.engineCast(engine.engineEval("exists(\"" + SchedulerConstants.RESULT_METADATA_VARIABLE + "\")", ctx), Boolean.class, ctx)) {
            Object metadataRexp = engine.engineEval(SchedulerConstants.RESULT_METADATA_VARIABLE, ctx);
            if (metadataRexp == null) {
                return;
            }
            Map newMap = engine.engineCast(metadataRexp, Map.class, ctx);
            metadata.putAll(newMap);
        }
    }

    /**
     * Assign the script arguments to the variable "args"
     */
    protected void assignArguments(Bindings bindings, ScriptContext ctx) {
        String[] args = (String[]) bindings.get(Script.ARGUMENTS_NAME);
        if (args == null) {
            return;
        }
        engine.engineSet("args", args, ctx);
    }

    /**
     * Assign results from previous tasks to the variable "results"
     */
    protected void assignResults(Bindings bindings, ScriptContext ctx) {
        TaskResult[] results = (TaskResult[]) bindings.get(SchedulerConstants.RESULTS_VARIABLE);
        if (results == null) {
            return;
        }
        Map<String, Object> resultsMap = new LinkedHashMap<>(results.length);
        for (TaskResult r : results) {
            Object value;
            try {
                value = r.value();
            } catch (Throwable e) {
                value = null;
            }
            resultsMap.put(r.getTaskId().getReadableName(), value);
        }
        engine.engineSet(SchedulerConstants.RESULTS_VARIABLE, resultsMap, ctx);
    }

    /**
     * assign the job variables into a R list called "variables"
     */
    protected Map<String, Serializable> assignVariables(Bindings bindings, ScriptContext ctx) {
        Map<String, Serializable> variables = (Map<String, Serializable>) bindings.get(SchedulerConstants.VARIABLES_BINDING_NAME);
        if (variables != null) {
            engine.engineSet(SchedulerConstants.VARIABLES_BINDING_NAME, variables, ctx);
        }
        return variables;
    }

    /**
     * assign the result metadata into a R list called "resultMetadata"
     */
    protected Map<String, String> assignResultMetadata(Bindings bindings, ScriptContext ctx) {
        Map<String, String> metadata = (Map<String, String>) bindings.get(SchedulerConstants.RESULT_METADATA_VARIABLE);
        if (metadata == null) {
            return null;
        }
        // If the map is empty, the R engine will convert it as a NULL object.
        // we need to add a dummy metadata to avoid this issue.
        if (metadata.isEmpty()) {
            metadata.put("r.result", "true");
        }
        engine.engineSet(SchedulerConstants.RESULT_METADATA_VARIABLE, metadata, ctx);

        return metadata;
    }


    /**
     * Assign a localspace variable which contains the location of the scratch space and change R current directory to it
     */
    protected void assignLocalSpace(Bindings bindings, ScriptContext ctx) {
        String localSpace = (String) bindings.get(SchedulerConstants.DS_SCRATCH_BINDING_NAME);

        if (localSpace == null) {
            return;
        }

        Path fpath = Paths.get(localSpace).normalize();
        if (Files.exists(fpath) && Files.isWritable(fpath)) {
            // convert it to be accepted by R
            localSpace = toRpath(fpath.toString());
            engine.engineEval("setwd('" + localSpace + "')", ctx);
            engine.engineSet("localspace", localSpace, ctx);
        }
    }

    /**
     * Assign variables which contain location of user|global|input|output space
     */
    protected void assignSpace(Bindings bindings, ScriptContext ctx, String bindingName) {
        String space = (String) bindings.get(bindingName);
        if (space == null) {
            return;
        }
        space = toRpath(space);
        engine.engineSet(bindingName, space, ctx);
    }


    /**
     * Create a function in the R Engine which allows to set the progress
     */
    protected void assignProgress(Bindings bindings, ScriptContext ctx) {

        Map<String, Serializable> variables = (Map<String, Serializable>) bindings.get(SchedulerConstants.VARIABLES_BINDING_NAME);
        if (variables != null) {
            this.taskProgressFile = (String) variables.get(SchedulerVars.PA_TASK_PROGRESS_FILE.toString());

            if (taskProgressFile != null) {
                this.taskProgressFile = toRpath(this.taskProgressFile.replace("\\", "/"));
                String command = ".set_progress <- function(x) { message('" + TASK_PROGRESS_MSG + "=', as.integer(x), appendLF = TRUE) }";
                engine.engineEval(command, ctx);
            }
        }

    }

    protected File createOuputFile(Bindings bindings) throws ScriptException {
        File outputFile;
        try {
            String localSpace = (String) bindings.get(SchedulerConstants.DS_SCRATCH_BINDING_NAME);
            if (localSpace == null) {
                localSpace = System.getProperty("java.io.tmpdir");
            }
            Path fpath = Paths.get(localSpace, ".Rout").normalize();
            outputFile = fpath.toFile();

            if (outputFile.exists()) {
                outputFile.delete();
            }
            outputFile.createNewFile();
            logger.info("Output file created : "+outputFile);
        } catch (IOException e) {
            throw new ScriptException(e);
        }
        return outputFile;
    }

    protected void customizeErrors(ScriptContext ctx) {
        engine.engineEval("options( error = function() { sysc = sys.calls(); sysc = sysc[1:length(sysc)-1]; cat('" + ERROR_TAG_BEGIN + "',geterrmessage(),'Call Stack :',  paste(rev(sysc), collapse ='\\n'),'" + ERROR_TAG_END + "', sep='\\n') })", ctx);
    }

    protected void prepareExecution(ScriptContext ctx, Bindings bindings) {
        this.enableWarnings(ctx);
        this.customizeErrors(ctx);
        this.assignArguments(bindings, ctx);
        this.assignProgress(bindings, ctx);
        this.assignResults(bindings, ctx);
        this.assignLocalSpace(bindings, ctx);
        this.assignSpace(bindings, ctx, SchedulerConstants.DS_USER_BINDING_NAME);
        this.assignSpace(bindings, ctx, SchedulerConstants.DS_GLOBAL_BINDING_NAME);
        this.assignSpace(bindings, ctx, SchedulerConstants.DS_INPUT_BINDING_NAME);
        this.assignSpace(bindings, ctx, SchedulerConstants.DS_OUTPUT_BINDING_NAME);
        this.assignVariables(bindings, ctx);
        this.assignResultMetadata(bindings, ctx);
    }

    protected String filterErrorsAndProgress(String text, boolean addNL) {
        if (text.contains(ERROR_TAG_BEGIN)) {
            readError = true;
            int bi = text.indexOf(ERROR_TAG_BEGIN) + ERROR_TAG_BEGIN.length();
            error.append(text.substring(bi) + (addNL ? "\n" : ""));
            text = text.replace(ERROR_TAG_BEGIN, "");
        } else if (text.contains(ERROR_TAG_END)) {
            int bi = text.indexOf(ERROR_TAG_END);
            error.append(text.substring(0, bi) + (addNL ? "\n" : ""));
            text = text.replace(ERROR_TAG_END, "");
            lastErrorMessage = error.toString();
            readError = false;
        } else if (text.startsWith(TASK_PROGRESS_MSG)) {
            Integer value = Integer.parseInt(text.split("=")[1].trim());
            ProgressFile.setProgress(taskProgressFile, value);
            text = text.replace(TASK_PROGRESS_MSG + "=" + value, "");
        } else if (readError) {
            error.append(text + (addNL ? "\n" : ""));
        }
        return text;
    }
}
