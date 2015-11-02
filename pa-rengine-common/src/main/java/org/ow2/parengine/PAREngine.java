package org.ow2.parengine;

import org.apache.log4j.Logger;
import org.ow2.proactive.scheduler.common.task.TaskResult;
import org.ow2.proactive.scheduler.task.SchedulerVars;
import org.ow2.proactive.scripting.Script;
import org.ow2.proactive.scripting.TaskScript;

import javax.script.*;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstract R implementation of ScriptEngine.
 *
 * @author Activeeon Team
 */
public abstract class PAREngine extends AbstractScriptEngine {

    public static final String IS_FORKED = "is.forked";
    public static final String DS_SCRATCH_BINDING_NAME = "localspace";
    public static final String DS_INPUT_BINDING_NAME = "inputspace";
    public static final String DS_OUTPUT_BINDING_NAME = "outputspace";
    public static final String DS_GLOBAL_BINDING_NAME = "globalspace";
    public static final String DS_USER_BINDING_NAME = "userspace";
    public static final String TASK_SCRIPT_VARIABLES = "variables";
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

    protected static boolean isInForkedTask() {
        return "true".equals(System.getProperty(IS_FORKED));
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
        if (engine.engineCast(engine.engineEval("exists(\"" + TASK_SCRIPT_VARIABLES + "\")", ctx), Boolean.class, ctx)) {
            Object variablesRexp = engine.engineEval(TASK_SCRIPT_VARIABLES, ctx);
            if (variablesRexp == null) {
                return;
            }
            Map newMap = engine.engineCast(variablesRexp, Map.class, ctx);
            jobVariables.putAll(newMap);
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
        TaskResult[] results = (TaskResult[]) bindings.get(TaskScript.RESULTS_VARIABLE);
        if (results == null) {
            return;
        }
        Map<String, Object> resultsMap = new HashMap<String, Object>(results.length);
        for (TaskResult r : results) {
            Object value;
            try {
                value = r.value();
            } catch (Throwable e) {
                value = null;
            }
            resultsMap.put(r.getTaskId().getReadableName(), value);
        }
        engine.engineSet(TaskScript.RESULTS_VARIABLE, resultsMap, ctx);
    }

    /**
     * assign the job variables into a R list called "variables"
     */
    protected Map<String, Serializable> assignVariables(Bindings bindings, ScriptContext ctx) {
        Map<String, Serializable> variables = (Map<String, Serializable>) bindings.get(TASK_SCRIPT_VARIABLES);
        if (variables != null) {
            engine.engineSet(TASK_SCRIPT_VARIABLES, variables, ctx);
        }
        return variables;
    }


    /**
     * Assign a localspace variable which contains the location of the scratch space and change R current directory to it
     */
    protected void assignLocalSpace(Bindings bindings, ScriptContext ctx) {
        String localSpace = (String) bindings.get(DS_SCRATCH_BINDING_NAME);

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
    protected void assignSpace(Bindings bindings, ScriptContext ctx, String bindingName, String varName) {
        String space = (String) bindings.get(bindingName);
        if (space == null) {
            return;
        }
        space = toRpath(space);
        engine.engineSet(varName, space, ctx);
    }


    /**
     * Create a function in the R Engine which allows to set the progress
     */
    protected void assignProgress(Bindings bindings, ScriptContext ctx) {

        Map<String, Serializable> variables = (Map<String, Serializable>) bindings.get(TASK_SCRIPT_VARIABLES);
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
            String localSpace = (String) bindings.get(DS_SCRATCH_BINDING_NAME);
            if (localSpace == null) {
                localSpace = System.getProperty("java.io.tmpdir");
            }
            Path fpath = Paths.get(localSpace, ".Rout").normalize();
            outputFile = fpath.toFile();

            if (outputFile.exists()) {
                outputFile.delete();
            }
            outputFile.createNewFile();
        } catch (IOException e) {
            throw new ScriptException(e);
        }
        return outputFile;
    }

    protected void customizeErrors(ScriptContext ctx) {
        engine.engineEval("options( error = function() { sysc = sys.calls(); sysc = sysc[1:length(sysc)-1]; cat('" + ERROR_TAG_BEGIN + "',geterrmessage(),'Call Stack :',  paste(rev(sysc), collapse ='\\n'),'" + ERROR_TAG_END + "', sep='\\n') })", ctx);
    }
}
