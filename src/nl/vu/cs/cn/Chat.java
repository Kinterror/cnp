package nl.vu.cs.cn;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class Chat extends Activity{

	Button btnTop, btnBottom;
	TextView tvTop, tvBottom;
	EditText etTop, etBottom;
	
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		btnTop = (Button)findViewById(R.id.btnTop);
		tvTop = (TextView)findViewById(R.id.tvTop);
		etTop = (EditText)findViewById(R.id.etTop);
		
		btnBottom = (Button)findViewById(R.id.btnBottom);
		tvBottom = (TextView)findViewById(R.id.tvBottom);
		etBottom = (EditText)findViewById(R.id.etBottom);
		
		
	}
	
	
}
