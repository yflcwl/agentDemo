package com.ex.yagent.claude.tools.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ClaudeTool {

    String name();

    String description();

    String schemaMethod();

    boolean hiddenInSubagent() default false;

    boolean hiddenInTeammate() default false;
}
