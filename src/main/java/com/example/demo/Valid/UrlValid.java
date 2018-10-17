package com.example.demo.Valid;

import javax.validation.Constraint;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD,ElementType.FIELD})  //注解作用域
@Retention(RetentionPolicy.RUNTIME)  //注解作用时间
@Constraint(validatedBy = ConstraintValidator.class) //执行校验逻辑的类
public @interface UrlValid {
    String message() default "must be date pattern";
    public abstract java.lang.Class[] groups() default {};
    public abstract java.lang.Class[] payload() default {};
}
