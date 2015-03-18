package io.example.skeletonserver;

/**
 * Created with IntelliJ IDEA.
 * User: gholla
 * Date: 3/5/15
 * Time: 4:00 PM
 * To change this template use File | Settings | File Templates.
 */
public class TestClass {

  public static void main(String[] args)
      throws Exception {
    A b = new B();
    A c = new C();

    b.printName();
    c.printName();
  }
}

class A {
  public void printName(){
    System.out.println("This is A");
  }
}

class B extends A {
  @Override
  public void printName(){
    System.out.println("This is B");
  }
}

class C extends A {
  @Override
  public void printName(){
    System.out.println("This is C");
  }
}
