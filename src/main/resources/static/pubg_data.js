const now=new Date();
const nowYear=now.getFullYear();
const nowMonth=now.getMonth()+1;
const nowDate=now.getDate();
const t=document.getElementById('date');
t.innerText=nowYear+'/'+nowMonth+'/'+nowDate;
    
const toggleBtn=document.querySelectorAll('.toggleBtn');
const match_detail=document.querySelectorAll('.match_detail');

for (let i = 0; i < 20; i++) {
    toggleBtn[i].addEventListener('click', ()=>{
        match_detail[i].classList.toggle('active');
    });
  }

function id_search(){
    const searchitem=document.querySelectorAll('.id');
    window.location.replace("/user/"+searchitem.value);
    }