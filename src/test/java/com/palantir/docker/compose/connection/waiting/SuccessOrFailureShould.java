package com.palantir.docker.compose.connection.waiting;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class SuccessOrFailureShould {
    @Test
    public void not_have_failed_if_actually_a_success() {
        assertThat(SuccessOrFailure.success().failed(), is(false));
    }
}