package com.parkingconcepts.mortimer

import kotlin.math.*

internal fun dbGain(db:Double)=10.0.pow(db/20.0)
internal fun gainDb(gain:Double)=20.0*log10(max(1e-9,gain))
internal class Biquad {
    private val c=doubleArrayOf(1.0,0.0,0.0,0.0,0.0)
    private val target=c.copyOf()
    private var z1=0.0;private var z2=0.0
    fun set(values:DoubleArray,immediate:Boolean=false){ for(i in 0..4){target[i]=values[i];if(immediate)c[i]=values[i]} }
    fun sample(x:Double):Double {
        for(i in 0..4)c[i]+=(target[i]-c[i])*.004
        val y=c[0]*x+z1;z1=c[1]*x-c[3]*y+z2;z2=c[2]*x-c[4]*y
        if(!y.isFinite()){z1=0.0;z2=0.0;return 0.0};return y
    }
    companion object {
        fun coefficients(kind:Int,hz:Double,db:Double,q:Double,sr:Int):DoubleArray {
            if(kind==0&&abs(db)<.0001 || hz>=sr*.475) return doubleArrayOf(1.0,0.0,0.0,0.0,0.0)
            val w=2*PI*hz.coerceIn(10.0,sr*.475)/sr;val cs=cos(w);val alpha=sin(w)/(2*q);val a=10.0.pow(db/40)
            val raw=when(kind){
                1->doubleArrayOf((1+cs)/2,-(1+cs),(1+cs)/2,1+alpha,-2*cs,1-alpha)
                2->doubleArrayOf((1-cs)/2,1-cs,(1-cs)/2,1+alpha,-2*cs,1-alpha)
                else->doubleArrayOf(1+alpha*a,-2*cs,1-alpha*a,1+alpha/a,-2*cs,1-alpha/a)
            }
            return doubleArrayOf(raw[0]/raw[3],raw[1]/raw[3],raw[2]/raw[3],raw[4]/raw[3],raw[5]/raw[3])
        }
        fun response(c:DoubleArray,hz:Double,sr:Int):Double {
            val w=2*PI*hz/sr;val cr=cos(w);val ci=-sin(w);val c2r=cos(2*w);val c2i=-sin(2*w)
            return sqrt(((c[0]+c[1]*cr+c[2]*c2r).pow(2)+(c[1]*ci+c[2]*c2i).pow(2))/((1+c[3]*cr+c[4]*c2r).pow(2)+(c[3]*ci+c[4]*c2i).pow(2)))
        }
    }
}
internal class FractionalDelay(val size:Int){
    private val data=FloatArray(size);var cursor=0;private set
    fun write(v:Double){data[cursor]=v.toFloat();cursor=(cursor+1)%size}
    fun read(frames:Double):Double {
        var pos=cursor-1-frames.coerceIn(0.0,size-2.0);while(pos<0)pos+=size
        val i=pos.toInt()%size;val t=pos-floor(pos);return data[i]*(1-t)+data[(i+1)%size]*t
    }
}
internal data class AudioMeters(val sampleRate:Int=0,val channels:Int=0,val peak:Float=0f,val rms:Float=0f,val reductionDb:Float=0f,
    val headroomDb:Float=0f,val detectedHz:Float=0f,val targetHz:Float=0f,val confidence:Float=0f,val spectrum:List<Float> = List(10){-90f})

internal class AudioAnalyzer(private val sr:Int){
    private val n=1024;private val ring=DoubleArray(n);private var at=0;private var total=0
    private val re=DoubleArray(n);private val im=DoubleArray(n);private val win=DoubleArray(n){.5-.5*cos(2*PI*it/(n-1))}
    var levels=List(10){-90f};private set
    fun push(value:Double){ring[at]=value;at=(at+1)%n;total++;if(total%n==0)analyze()}
    private fun analyze(){
        for(i in 0 until n){re[i]=ring[(at+i)%n]*win[i];im[i]=0.0}
        var j=0
        for(i in 1 until n){var bit=n shr 1;while(j and bit !=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val r=re[i];re[i]=re[j];re[j]=r}}
        var len=2
        while(len<=n){val angle=-2*PI/len;val wr0=cos(angle);val wi0=sin(angle);var start=0
            while(start<n){var wr=1.0;var wi=0.0;for(k in 0 until len/2){val u=start+k;val v=u+len/2;val vr=re[v]*wr-im[v]*wi;val vi=re[v]*wi+im[v]*wr
                re[v]=re[u]-vr;im[v]=im[u]-vi;re[u]+=vr;im[u]+=vi;val next=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=next};start+=len};len*=2}
        levels=AudioEffects.BANDS.map { hz->
            val low=max(1,(hz/sqrt(2.0)*n/sr).toInt());val high=min(n/2,(hz*sqrt(2.0)*n/sr).toInt().coerceAtLeast(low));var peak=0.0
            if(low<=high)for(k in low..high)peak=max(peak,hypot(re[k],im[k])*4/n)
            gainDb(peak).toFloat().coerceIn(-90f,0f)
        }
    }
}
internal fun audioEqResponse(s:AudioEffects.Settings,hz:Double,sr:Int=48000):Double {
    var response=1.0
    AudioEffects.BANDS.forEachIndexed{i,f->response*=Biquad.response(Biquad.coefficients(0,f.toDouble(),s.bands[i].toDouble(),1.0,sr),hz,sr)}
    if(s.highPass)response*=Biquad.response(Biquad.coefficients(1,s.highPassHz.toDouble(),0.0,s.resonance.toDouble(),sr),hz,sr)
    if(s.lowPass)response*=Biquad.response(Biquad.coefficients(2,s.lowPassHz.toDouble(),0.0,s.resonance.toDouble(),sr),hz,sr)
    return gainDb(response)
}
