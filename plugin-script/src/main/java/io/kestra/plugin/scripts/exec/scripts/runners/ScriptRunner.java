package io.kestra.plugin.scripts.exec.scripts.runners;

public interface ScriptRunner {
    RunnerResult run(CommandsWrapper commands) throws Exception;
}
