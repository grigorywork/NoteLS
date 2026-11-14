import java.util.Random;
import java.util.Arrays;
public class RandomFixtureReplay {
 public static void main(String[] args) {
  Random r = new Random(202611143L); long[] counts = new long[11];
  for(int iteration=0;iteration<=10523;iteration++) {
   int c=r.nextInt(11); int first=-1,second=-1,variant=-1;
   switch(c) {
    case 0: r.nextInt(600);r.nextInt(600);break;
    case 1: r.nextBoolean();break;
    case 2: r.nextInt(600);r.nextInt(600);break;
    case 3: r.nextInt(10000);r.nextBoolean();break;
    case 4: r.nextBoolean();r.nextInt(4);break;
    case 5: r.nextInt(600);break;
    case 6: break;
    case 7: r.nextInt(600);r.nextBoolean();r.nextInt(10000);break;
    case 8: r.nextInt(3600000);first=r.nextInt(600);variant=r.nextInt(3);if(variant==0)second=r.nextInt(600);break;
    case 9: r.nextInt(10000);break;
    case 10: r.nextBoolean();break;
   }
   if(iteration==10523) {
    System.out.println("{\"seed\":202611143,\"iteration\":"+iteration+",\"case\":"+c+",\"variant\":"+variant+",\"old_repetitions\":"+(first+1)+",\"new_repetitions\":"+(second+1)+",\"before_case_counts\":"+Arrays.toString(counts)+",\"fixture_text_equal\":"+(first==second)+"}");
    if(c!=8 || variant!=0 || first!=second) throw new AssertionError("Replay did not match failure");
   }
   counts[c]++;
  }
 }
}
