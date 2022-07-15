package com.example.okhtt_okgo;

import org.junit.Test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void test() throws IOException {
        File file = new File("","a");
        if (!file.exists()){
            file.createNewFile();
        }
        file.renameTo(new File(file.getParent(),"a"));
    }
}