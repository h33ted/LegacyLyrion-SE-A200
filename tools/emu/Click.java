import java.awt.Robot; import java.awt.event.InputEvent; import java.awt.event.KeyEvent;
public class Click { public static void main(String[] a) throws Exception { Robot r=new Robot();
 for (int i=0;i<a.length;i++){ String s=a[i];
  if (s.startsWith("k:")) { int k=KeyEvent.class.getField("VK_"+s.substring(2)).getInt(null); r.keyPress(k); r.delay(60); r.keyRelease(k); }
  else if (s.startsWith("w:")) r.delay(Integer.parseInt(s.substring(2)));
  else { String[] p=s.split(","); r.mouseMove(Integer.parseInt(p[0]),Integer.parseInt(p[1])); r.delay(80); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); r.delay(80); r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);} r.delay(150);} } }
