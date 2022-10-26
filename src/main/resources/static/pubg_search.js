const now=new Date();
const nowYear=now.getFullYear();
const nowMonth=now.getMonth()+1;
const nowDate=now.getDate();
const t=document.getElementById('date');
t.innerText=nowYear+'/'+nowMonth+'/'+nowDate;

function redirect(){
    const searchitem=document.getElementById("search_text");
    window.location.href="/user/"+searchitem.value;
}
