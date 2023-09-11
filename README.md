# BaseDownloader
Example for a Rest-WebService to download a resource and return it BASE64 encoded

## Hints

To open multiple tabs from a HTML page use:

```
<script>
function openLinks(){
links = document.getElementsByTagName('a');

 for (i = 0; i < links.length;i++){ 
   window.open(links[i].getAttribute('href'),'_blank');
   window.focus();
 }
}
</script>
```

and:

```
<body onload="openLinks()">

<a href="http://google.com">google</a>
<a href="http://stackoverflow.com">stackoverflow</a>
<a href="http://facebook.com">facebook</a>
```
