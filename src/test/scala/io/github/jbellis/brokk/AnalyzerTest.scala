package io.github.jbellis.brokk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows, assertTrue}
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.semanticcpg.language.*

import java.nio.file.Path
import scala.jdk.javaapi.*
import scala.jdk.javaapi.CollectionConverters.{asJava, asScala}

class AnalyzerTest {
  implicit val callResolver: ICallResolver = NoResolve

  @Test
  def callerTest(): Unit = {
    val analyzer = getAnalyzer
    val callOut = analyzer.cpg.method.call.l
    assert(callOut.size > 0)
    val callIn = analyzer.cpg.method.caller.l
    assert(callIn.size > 0)
  }

  @Test
  def isClassInProjectTest(): Unit = {
    val analyzer = getAnalyzer
    assert(analyzer.isClassInProject("A"))

    assert(!analyzer.isClassInProject("java.nio.filename.Path"))
    assert(!analyzer.isClassInProject("org.foo.Bar"))
  }
  
  @Test
  def extractsMethodSource(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("A.method2").get

    val expected =
      """    public String method2(String input) {
        |        return "prefix_" + input;
        |    }
        |
        |    public String method2(String input, int otherInput) {
        |        // overload of method2
        |        return "prefix_" + input + " " + otherInput;
        |    }""".stripMargin

    assertEquals(expected, source)
  }

  @Test 
  def sanitizeTypeTest(): Unit = {
    val analyzer = getAnalyzer
    
    // Simple types
    assertEquals("String", analyzer.sanitizeType("java.lang.String"))
    assertEquals("String[]", analyzer.sanitizeType("java.lang.String[]"))
    
    // Generic types
    assertEquals("Function<Integer, Integer>", 
      analyzer.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>"))
    
    // Nested generic types
    assertEquals("Map<String, List<Integer>>",
      analyzer.sanitizeType("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>"))
      
    // Method return type with generics
    assertEquals("Function<Integer, Integer>",
      analyzer.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>"))
  }

  @Test
  def getSkeletonTestA(): Unit = {
    val skeleton = getAnalyzer.getSkeleton("A").get
    // https://github.com/joernio/joern/issues/5297
//    val expected =
//      """class A {
//        |  public void method1() {...}
//        |  public String method2(String input) {...}
//        |  public Function<Integer, Integer> method3() {...}
//        |}""".stripMargin
    val expected =
      """class A {
        |  public void method1() {...}
        |  public String method2(String input) {...}
        |  public String method2(String input, int otherInput) {...}
        |  public Function method3() {...}
        |  public static int method4(double foo, Integer bar) {...}
        |  public void method5() {...}
        |  public void method6() {...}
        |}""".stripMargin
    assertEquals(expected, skeleton)
  }

  @Test
  def getSkeletonTestD(): Unit = {
    val skeleton = getAnalyzer.getSkeleton("D").get
    val expected =
      """class D {
        |  private int field1;
        |  private String field2;
        |  public void methodD1() {...}
        |  public void methodD2() {...}
        |}""".stripMargin
    assertEquals(expected, skeleton)
  }

  @Test
  def getGetSkeletonHeaderTest(): Unit = {
    val skeleton = getAnalyzer.getSkeletonHeader("D").get
    val expected =
      """class D {
        |  private int field1;
        |  private String field2;
        |  [... methods not shown ...]
        |}""".stripMargin
    assertEquals(expected, skeleton)
  }

  @Test
  def getAllClassesTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.getAllClasses
  }

  @Test
  def getMembersInClassTest(): Unit = {
    val analyzer = getAnalyzer
    val members = analyzer.getMembersInClass("D")
    val expected = Set("D.field1", "D.field2").map(CodeUnit.field) ++ Set("D.methodD1", "D.methodD2").map(CodeUnit.fn) ++ Set("D$DSub", "D$DSubStatic").map(CodeUnit.cls)
    assertEquals(expected, asScala(members).toSet)
  }

  @Test
  def getReferrersOfFieldTest(): Unit = {
    val analyzer = getAnalyzer
    val referrers = analyzer.getReferrersOfField("D.field1")
    assert(referrers.contains("D.methodD2"))
  }

  @Test
  def getCallersOfMethodTest(): Unit = {
    val analyzer = getAnalyzer
    val callers = analyzer.getCallersOfMethod("A.method1")
    assert(callers.contains("B.callsIntoA"))
    assert(callers.contains("D.methodD1"))
  }

  @Test
  def getPagerankTest(): Unit = {
    val analyzer = getAnalyzer
    import scala.jdk.javaapi._
    
    val seeds = asJava(List("D"))
    val ranked = analyzer.getPagerank(seeds, 3)
    
    // A and B should rank highly as they are both called by D
    assert(ranked.size() == 3, ranked)
    val classes = asScala(ranked).map(_._1).toSet
    assertEquals(Set("A", "B", "AnonymousUsage.foo.Runnable$0"), classes)
  }

  @Test
  def getClassesInFileTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.getClassesInFile(analyzer.toFile("D.java"))
    val expected = Set("D", "D$DSub", "D$DSubStatic").map(CodeUnit.cls)
    assertEquals(expected, asScala(classes).toSet)
  }

  @Test 
  def classesInPackagedFileTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.getClassesInFile(analyzer.toFile("Packaged.java"))
    assertEquals(Set(CodeUnit.cls("io.github.jbellis.brokk.Foo")), asScala(classes).toSet)
  }

  @Test
  def getUsesMethodExistingTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "A.method2"
    val usages = analyzer.getUses(symbol)

    // Expect references in B.callsIntoA() because it calls a.method2("test")
    val actualMethodRefs = asScala(usages).filter(_.isFunction).map(_.reference).toSet
    val actualRefs = asScala(usages).map(_.reference).toSet
    assertEquals(Set("B.callsIntoA", "AnonymousUsage.foo"), actualRefs)
  }

  @Test
  def getUsesMethodNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "A.noSuchMethod:java.lang.String()"
    val ex = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("not found as a method, field, or class"))
  }

  @Test
  def getUsesFieldExistingTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "D.field1" // fully qualified field name
    val usages = analyzer.getUses(symbol)

    // We expect methodD2 references "field1 = 42"
    val actualRefs = asScala(usages).map(_.reference).toSet
    assertEquals(Set("D.methodD2"), actualRefs)
  }

  @Test
  def getUsesFieldNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "D.notAField"
    val ex = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("not found"))
  }

  @Test
  def getUsesClassBasicTest(): Unit = {
    val analyzer = getAnalyzer
    // “A” has no static members, but it is used as a local type in B.callsIntoA and D.methodD1
    val symbol = "A"
    val usages = analyzer.getUses(symbol)

    // methodUses => references to A as a type in B.callsIntoA() and D.methodD1()
    val foundRefs = asScala(usages).map(_.reference).toSet
    assertEquals(Set("B.callsIntoA", "D.methodD1", "AnonymousUsage.foo"), foundRefs)
  }

  @Test
  def getUsesClassNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "NoSuchClass"
    val ex = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("Symbol 'NoSuchClass' not found"))
  }

  @Test
  def getUsesClassWithStaticMembersTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "E"
    val usages = analyzer.getUses(symbol)

    val refs = asScala(usages).map(_.reference).toSet
    assertEquals(Set("UseE.some", "UseE.<init>", "UseE.moreM", "UseE.moreF", "UseE"), refs)
  }

  //
  // Helper to get a prebuilt analyzer
  //
  private def getAnalyzer = {
    // Points to your test code directory with A, B, C, D, CamelClass, E, UseE, etc.
    new Analyzer(Path.of("src/test/resources/testcode"))
  }
}
